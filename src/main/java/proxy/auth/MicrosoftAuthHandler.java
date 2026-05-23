package proxy.auth;

import com.google.gson.Gson;
import java.time.LocalDateTime;
import java.util.Base64;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.MultipartBody;
import kong.unirest.Unirest;
import kong.unirest.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Handles logging in using Microsoft authentication.
 */
public class MicrosoftAuthHandler {
    private static final int PBKDF2_ITERATIONS = 65_536;
    private static final int KEY_LENGTH_BITS = 256;
    private static final int GCM_TAG_LENGTH_BITS = 128;
    private static final int SALT_LENGTH = 16;
    private static final int IV_LENGTH = 12;

    // this should probably be obfuscated but not worth the effort
    private final static String CLIENT_ID = "99ef6720-81b8-4bf7-a653-d6f429f1cea3";

    private static final String REDIRECT_URL = "http://localhost:%d/world-downloader-auth-complete";
    public static final String LOGIN_URL = "https://login.live.com/oauth20_authorize.srf" +
        "?client_id=" + CLIENT_ID +
        "&response_type=code" +
        "&scope=XboxLive.signin%%20offline_access" +
        "&redirect_uri=" + REDIRECT_URL +
        "&prompt=select_account";

    public static final String REDIRECT_SUFFIX = REDIRECT_URL + "?code=";

    private transient String authCode;

    private final int usedPort;
    private transient String userHash;
    private transient String microsoftAccessToken;
    private transient String microsoftRefreshToken;
    private LocalDateTime microsoftExpiration = LocalDateTime.MIN;

    private transient String xboxLiveToken;
    private LocalDateTime xboxLiveExpiration = LocalDateTime.MIN;

    private transient String xboxSecurityToken;
    private LocalDateTime xboxSecurityExpiration = LocalDateTime.MIN;

    private transient String minecraftAccessToken;
    private LocalDateTime minecraftAccessExpiration = LocalDateTime.MIN;

    private transient AuthDetails authDetails;

    private String savedRefreshToken;
    private String savedRefreshTokenSalt;
    private String savedRefreshTokenIv;
    private boolean savedRefreshTokenEncrypted;

    public MicrosoftAuthHandler(String authCode, int usedPort) {
        this.authCode = authCode;
        this.usedPort = usedPort;
    }

    public static String getLoginUrl(int port) {
        return String.format(LOGIN_URL, port);
    }

    private String getRedirectUrl() {
        return String.format(REDIRECT_URL, usedPort);
    }

    private void refresh() {
        loadSavedRefreshTokenIfAvailable();

        LocalDateTime now = LocalDateTime.now();
        if (microsoftAccessToken == null || microsoftExpiration.isBefore(now)) {
            acquireMicrosoftToken();
        }

        if (xboxLiveToken == null || xboxLiveExpiration.isBefore(now)) {
            acquireXboxLiveToken();
        }

        if (xboxSecurityToken == null || xboxSecurityExpiration.isBefore(now)) {
            acquireXboxSecurityToken();
        }

        if (minecraftAccessToken == null || minecraftAccessExpiration.isBefore(now)) {
            acquireMinecraftAccessToken();
        }
    }



    public static MicrosoftAuthHandler fromCode(String authCode, int usedPort) {
        MicrosoftAuthHandler msAuth = new MicrosoftAuthHandler(authCode, usedPort);
        msAuth.refresh();
        return msAuth;
    }

    private void acquireMicrosoftToken() {
        if ((this.authCode == null || this.authCode.isBlank())
            && (this.microsoftRefreshToken == null || this.microsoftRefreshToken.isBlank())) {
            throw new MinecraftAuthenticationException("No Microsoft login session found. Please log in again.");
        }

        MultipartBody body = Unirest.post("https://login.live.com/oauth20_token.srf")
            .contentType("application/x-www-form-urlencoded")
            .field("client_id", CLIENT_ID)
            .field("redirect_uri", getRedirectUrl());

        if (this.microsoftRefreshToken == null) {
            body = body.field("code", this.authCode)
                .field("grant_type", "authorization_code");
        } else {
            body = body.field("refresh_token", this.microsoftRefreshToken)
                .field("grant_type", "refresh_token");
        }

        HttpResponse<JsonNode> res = body.asJson();
        if (!res.isSuccess()) {
            throw buildAuthException("Microsoft token", res);
        }

        this.microsoftAccessToken = res.getBody().getObject().getString("access_token");
        this.microsoftRefreshToken = res.getBody().getObject().getString("refresh_token");

        int expiresIn = res.getBody().getObject().getInt("expires_in");
        this.microsoftExpiration = LocalDateTime.now().plusSeconds(expiresIn);

        // invalidate others
        xboxLiveExpiration = LocalDateTime.MIN;
        xboxSecurityExpiration = LocalDateTime.MIN;
        minecraftAccessExpiration = LocalDateTime.MIN;
    }

    private void acquireXboxLiveToken() {
        HttpResponse<JsonNode> res = Unirest.post("https://user.auth.xboxlive.com/user/authenticate")
            .body(new XboxLiveBody(this.microsoftAccessToken).toString())
            .contentType("application/json")
            .accept("application/json")
            .asJson();

        if (!res.isSuccess()) {
            throw buildAuthException("Xbox Live token", res);
        }

        JSONObject jso = res.getBody().getObject();

        this.xboxLiveToken = jso.getString("Token");
        this.userHash = jso.getJSONObject("DisplayClaims")
            .getJSONArray("xui")
            .getJSONObject(0)
            .getString("uhs");
        this.xboxLiveExpiration = LocalDateTime.parse(jso.getString("NotAfter").split("\\.")[0]);

        // invalidate others
        xboxSecurityExpiration = LocalDateTime.MIN;
        minecraftAccessExpiration = LocalDateTime.MIN;
    }

    private void acquireXboxSecurityToken() {
        //  TODO: handle realms token
        HttpResponse<JsonNode> res = Unirest.post("https://xsts.auth.xboxlive.com/xsts/authorize")
            .body(new XboxSecurityBody(this.xboxLiveToken).toString())
            .contentType("application/json")
            .accept("application/json")
            .asJson();

        if (!res.isSuccess()) {
            throw buildAuthException("Xbox security token", res);
        }

        JSONObject jso = res.getBody().getObject();
        this.xboxSecurityToken = jso.getString("Token");
        this.xboxSecurityExpiration = LocalDateTime.parse(jso.getString("NotAfter").split("\\.")[0]);

        // invalidate others
        minecraftAccessExpiration = LocalDateTime.MIN;
    }

    private void acquireMinecraftAccessToken() {
        HttpResponse<JsonNode> res = Unirest.post("https://api.minecraftservices.com/authentication/login_with_xbox")
            .body(new MinecraftAuthBody(this.userHash, this.xboxSecurityToken).toString())
            .contentType("application/json")
            .accept("application/json")
            .asJson();

        if (!res.isSuccess()) {
            throw buildAuthException("Minecraft token", res);
        }

        JSONObject jso = res.getBody().getObject();
        this.minecraftAccessToken = jso.getString("access_token");
        this.minecraftAccessExpiration = LocalDateTime.now().plusSeconds(jso.getInt("expires_in"));
    }

    public AuthDetails getAuthDetails() {
        if (authDetails == null) {
            this.refresh();
            authDetails = AuthDetails.fromAccessToken(this.minecraftAccessToken);
        }
        return authDetails;
    }

    public boolean hasLoggedIn() {
        return (this.authCode != null && !this.authCode.isEmpty())
            || (this.microsoftRefreshToken != null && !this.microsoftRefreshToken.isEmpty())
            || hasSavedSession();
    }

    public boolean hasSavedSession() {
        return savedRefreshToken != null && !savedRefreshToken.isBlank();
    }

    public boolean needsPassword() {
        return savedRefreshTokenEncrypted && (this.microsoftRefreshToken == null || this.microsoftRefreshToken.isBlank());
    }

    public boolean isSavedSessionEncrypted() {
        return savedRefreshTokenEncrypted;
    }

    public void saveSession(String password) {
        if (this.microsoftRefreshToken == null || this.microsoftRefreshToken.isBlank()) {
            throw new MinecraftAuthenticationException("No Microsoft refresh token is available to save.");
        }

        if (password == null || password.isBlank()) {
            savedRefreshToken = this.microsoftRefreshToken;
            savedRefreshTokenSalt = null;
            savedRefreshTokenIv = null;
            savedRefreshTokenEncrypted = false;
            return;
        }

        byte[] salt = randomBytes(SALT_LENGTH);
        byte[] iv = randomBytes(IV_LENGTH);
        byte[] encrypted = encrypt(password, salt, iv, this.microsoftRefreshToken);

        savedRefreshToken = Base64.getEncoder().encodeToString(encrypted);
        savedRefreshTokenSalt = Base64.getEncoder().encodeToString(salt);
        savedRefreshTokenIv = Base64.getEncoder().encodeToString(iv);
        savedRefreshTokenEncrypted = true;
    }

    public void clearSavedSession() {
        savedRefreshToken = null;
        savedRefreshTokenSalt = null;
        savedRefreshTokenIv = null;
        savedRefreshTokenEncrypted = false;
    }

    public void unlockSavedSession(String password) {
        if (!savedRefreshTokenEncrypted) {
            loadSavedRefreshTokenIfAvailable();
            return;
        }
        if (password == null || password.isBlank()) {
            throw new MinecraftAuthenticationException("Password is required to unlock the saved Microsoft login.");
        }
        if (!hasSavedSession()) {
            throw new MinecraftAuthenticationException("No saved Microsoft login is available.");
        }

        try {
            byte[] salt = Base64.getDecoder().decode(savedRefreshTokenSalt);
            byte[] iv = Base64.getDecoder().decode(savedRefreshTokenIv);
            byte[] ciphertext = Base64.getDecoder().decode(savedRefreshToken);
            this.microsoftRefreshToken = decrypt(password, salt, iv, ciphertext);
        } catch (Exception ex) {
            throw new MinecraftAuthenticationException("Could not unlock saved Microsoft login. Check the password.");
        }
    }

    private MinecraftAuthenticationException buildAuthException(String stage, HttpResponse<JsonNode> res) {
        JSONObject body = res.getBody() == null ? null : res.getBody().getObject();
        String description = null;
        if (body != null) {
            description = body.optString("error_description", null);
            if (description == null || description.isBlank()) {
                description = body.optString("error", null);
            }
        }

        String message = "Cannot get " + stage + ". Status: " + res.getStatus();
        if (description != null && !description.isBlank()) {
            message += " (" + description + ")";
        }
        return new MinecraftAuthenticationException(message);
    }

    private void loadSavedRefreshTokenIfAvailable() {
        if (this.microsoftRefreshToken != null && !this.microsoftRefreshToken.isBlank()) {
            return;
        }
        if (!hasSavedSession()) {
            return;
        }
        if (!savedRefreshTokenEncrypted) {
            this.microsoftRefreshToken = savedRefreshToken;
        }
    }

    private static byte[] randomBytes(int length) {
        byte[] out = new byte[length];
        new SecureRandom().nextBytes(out);
        return out;
    }

    private static byte[] encrypt(String password, byte[] salt, byte[] iv, String value) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
            return cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new MinecraftAuthenticationException("Could not encrypt the Microsoft login.");
        }
    }

    private static String decrypt(String password, byte[] salt, byte[] iv, byte[] ciphertext) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, deriveKey(password, salt), new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));
        return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
    }

    private static SecretKeySpec deriveKey(String password, byte[] salt) throws Exception {
        SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        KeySpec spec = new PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LENGTH_BITS);
        return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
    }


    private static class XboxLiveBody {
        String RelyingParty = "http://auth.xboxlive.com";
        String TokenType = "JWT";
        XboxLiveProperties Properties;

        public XboxLiveBody(String accessToken) {
            Properties = new XboxLiveProperties(accessToken);
        }

        private static class XboxLiveProperties {
            String AuthMethod = "RPS";
            String SiteName = "user.auth.xboxlive.com";
            String RpsTicket;

            public XboxLiveProperties(String accessToken) {
                RpsTicket = "d=" + accessToken;
            }
        }

        @Override
        public String toString() {
            return new Gson().toJson(this);
        }
    }

    private static class XboxSecurityBody {
        String RelyingParty = "rp://api.minecraftservices.com/";
        String TokenType = "JWT";
        XboxSecurityProperties Properties;

        public XboxSecurityBody(String xboxLiveToken) {
            Properties = new XboxSecurityProperties(xboxLiveToken);
        }

        private static class XboxSecurityProperties {
            String SandboxId = "RETAIL";
            String[] UserTokens;
            public XboxSecurityProperties(String xboxLiveToken) {
                this.UserTokens = new String[] { xboxLiveToken };
            }
        }

        @Override
        public String toString() {
            return new Gson().toJson(this);
        }
    }

    private static class MinecraftAuthBody {
        String identityToken;

        public MinecraftAuthBody(String userHash, String xboxSecurityToken) {
            this.identityToken = "XBL3.0 x=" + userHash + ";" + xboxSecurityToken;
        }

        @Override
        public String toString() {
            return new Gson().toJson(this);
        }
    }
}
