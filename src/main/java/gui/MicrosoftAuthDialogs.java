package gui;

import java.util.Optional;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import proxy.auth.MicrosoftAuthHandler;

public final class MicrosoftAuthDialogs {
    private MicrosoftAuthDialogs() { }

    public static SaveChoice promptToPersistLogin() {
        Dialog<SaveChoice> dialog = new Dialog<>();
        dialog.setTitle("Save Microsoft Login");
        dialog.setHeaderText("Save this Microsoft login for later use?");
        style(dialog);

        ButtonType saveButton = new ButtonType("Save Login", ButtonBar.ButtonData.OK_DONE);
        ButtonType skipButton = new ButtonType("Don't Save", ButtonBar.ButtonData.NO);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(saveButton, skipButton, cancelButton);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");

        VBox content = new VBox(10,
            new Label("Enter a password to encrypt the saved token."),
            new Label("Leave it blank if you are okay saving it unencrypted."),
            passwordField
        );
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        dialog.setResultConverter(button -> {
            if (button == saveButton) {
                return new SaveChoice(true, passwordField.getText());
            }
            if (button == skipButton) {
                return new SaveChoice(false, null);
            }
            return null;
        });

        Optional<SaveChoice> result = dialog.showAndWait();
        return result.orElse(new SaveChoice(false, null));
    }

    public static String unlockSavedLogin(MicrosoftAuthHandler handler) {
        if (handler == null || !handler.needsPassword()) {
            return null;
        }

        Dialog<String> dialog = new Dialog<>();
        dialog.setTitle("Unlock Microsoft Login");
        dialog.setHeaderText("Enter your password to unlock the saved Microsoft login.");
        style(dialog);

        ButtonType unlockButton = new ButtonType("Unlock", ButtonBar.ButtonData.OK_DONE);
        ButtonType cancelButton = new ButtonType("Cancel", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(unlockButton, cancelButton);

        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText("Password");
        VBox content = new VBox(10, new Label("This saved login is encrypted."), passwordField);
        content.setPadding(new Insets(10));
        dialog.getDialogPane().setContent(content);

        Node unlockNode = dialog.getDialogPane().lookupButton(unlockButton);
        unlockNode.setDisable(true);
        passwordField.textProperty().addListener((obs, oldVal, newVal) -> unlockNode.setDisable(newVal == null || newVal.isBlank()));

        dialog.setResultConverter(button -> button == unlockButton ? passwordField.getText() : null);

        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return "Saved Microsoft login was not unlocked.";
        }

        try {
            handler.unlockSavedSession(result.get());
            return null;
        } catch (RuntimeException ex) {
            return ex.getMessage();
        }
    }

    private static void style(Dialog<?> dialog) {
        dialog.setOnShown(event -> {
            Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
            GuiManager.addIcon(stage);
        });
        dialog.getDialogPane().getStylesheets().add(MicrosoftAuthDialogs.class.getResource("/ui/dark.css").toExternalForm());
    }

    public record SaveChoice(boolean save, String password) { }
}
