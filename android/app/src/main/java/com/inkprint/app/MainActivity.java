package com.inkprint.app;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.text.InputType;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.PickVisualMediaRequest;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.credentials.ClearCredentialStateRequest;
import androidx.credentials.Credential;
import androidx.credentials.CredentialManager;
import androidx.credentials.CustomCredential;
import androidx.credentials.GetCredentialRequest;
import androidx.credentials.exceptions.GetCredentialCancellationException;
import androidx.credentials.exceptions.GetCredentialException;
import androidx.credentials.exceptions.ClearCredentialException;

import com.google.android.libraries.identity.googleid.GetGoogleIdOption;
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential;
import com.google.firebase.FirebaseApp;
import com.google.firebase.auth.AuthCredential;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseUser;
import com.google.firebase.auth.EmailAuthProvider;
import com.google.firebase.auth.GoogleAuthProvider;
import com.google.firebase.auth.UserProfileChangeRequest;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends AppCompatActivity {
    private static final int COLOR_INK = Color.rgb(32, 40, 32);
    private static final int COLOR_MUTED = Color.rgb(112, 122, 113);
    private static final int COLOR_GREEN = Color.rgb(89, 85, 248);
    private static final int COLOR_BACKGROUND = Color.rgb(250, 250, 254);
    private static final int COLOR_BORDER = Color.rgb(230, 231, 241);
    private static final int COLOR_MATCH = Color.rgb(0, 166, 112);
    private static final int COLOR_CARD = Color.WHITE;
    private static final int COLOR_ERROR = Color.rgb(164, 62, 55);
    private static final float MAX_IMAGE_SIZE = 1100f;

    private final ExecutorService imageExecutor = Executors.newSingleThreadExecutor();
    private final Sample[] samples = new Sample[2];
    private final ActivityResultLauncher<PickVisualMediaRequest> firstImagePicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> onImagePicked(0, uri));
    private final ActivityResultLauncher<PickVisualMediaRequest> secondImagePicker =
            registerForActivityResult(new ActivityResultContracts.PickVisualMedia(), uri -> onImagePicked(1, uri));

    private CredentialManager credentialManager;
    private FirebaseAuth firebaseAuth;
    private FirebaseAuth.AuthStateListener authStateListener;
    private LinearLayout content;
    private TextView authMessage;
    private View authButton;
    private TextView compareButton;
    private TextView comparisonMessage;
    private EditText emailInput;
    private EditText passwordInput;
    private EditText nameInput;
    private EditText confirmPasswordInput;
    private EditText currentPasswordInput;
    private EditText newPasswordInput;
    private boolean authReady;
    private String authError = "";
    private HandwritingAnalyzer.Comparison comparison;
    private AppScreen screen = AppScreen.SIGN_IN;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        androidx.core.splashscreen.SplashScreen.installSplashScreen(this);
        super.onCreate(savedInstanceState);
        configureSystemBars();
        credentialManager = CredentialManager.create(this);
        initializeFirebase();
        restoreSamples(savedInstanceState);

        if (firebaseAuth != null) {
            authStateListener = auth -> renderCurrentScreen();
            firebaseAuth.addAuthStateListener(authStateListener);
        }
        renderCurrentScreen();
    }

    private void configureSystemBars() {
        EdgeToEdge.enable(this);
        Window window = getWindow();
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(Color.TRANSPARENT);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }

    private void initializeFirebase() {
        try {
            if (FirebaseApp.initializeApp(this) == null) {
                authError = "Firebase isn't configured. Add this Android app to your Firebase project and include its google-services.json.";
                return;
            }
            firebaseAuth = FirebaseAuth.getInstance();
            authReady = !BuildConfig.GOOGLE_WEB_CLIENT_ID.isEmpty()
                    && BuildConfig.GOOGLE_WEB_CLIENT_ID.matches(
                            "^[A-Za-z0-9._-]+\\.apps\\.googleusercontent\\.com$");
            if (!authReady) {
                authError = "Google sign-in needs a Firebase-enabled Web OAuth client ID in your local .env file.";
            }
        } catch (IllegalStateException exception) {
            authError = "Firebase could not initialize. Check the Android app configuration in Firebase.";
            android.util.Log.e("Inkprint", "Firebase initialization failed.", exception);
        }
    }

    private void renderCurrentScreen() {
        FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
        if (user == null) {
            switch (screen) {
                case SIGN_UP:
                    renderSignUp();
                    break;
                case RESET_PASSWORD:
                    renderResetPassword();
                    break;
                default:
                    screen = AppScreen.SIGN_IN;
                    renderSignIn();
                    break;
            }
            return;
        }
        switch (screen) {
            case HISTORY:
                renderHistory(user);
                break;
            case PROFILE:
                renderProfile(user);
                break;
            case CHANGE_PASSWORD:
                renderChangePassword(user);
                break;
            default:
                screen = AppScreen.COMPARE;
                renderComparisonApp(user);
                break;
        }
    }

    private void renderSignIn() {
        ScrollView page = createPage();
        content = createPageContent();
        page.addView(content);
        addAuthHeader(content);
        addSpace(content, 24);
        content.addView(text("Welcome back", 28, COLOR_INK, true));
        addSpace(content, 7);
        content.addView(text("Sign in to compare handwriting.", 14, COLOR_MUTED, false));
        addSpace(content, 22);
        emailInput = addInput(content, "Email", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        addSpace(content, 11);
        passwordInput = addInput(content, "Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addSpace(content, 5);
        TextView forgot = text("Forgot password?", 12, COLOR_GREEN, true);
        forgot.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        content.addView(forgot, matchWrap());
        forgot.setOnClickListener(view -> {
            screen = AppScreen.RESET_PASSWORD;
            renderCurrentScreen();
        });
        addSpace(content, 13);
        TextView signIn = actionButton("Sign In", COLOR_GREEN, Color.WHITE);
        content.addView(signIn, matchWrap());
        signIn.setOnClickListener(view -> signInWithEmail());
        addAuthMessage();
        addSeparator(content);
        authButton = googleSignInButton();
        authButton.setEnabled(authReady);
        authButton.setAlpha(authReady ? 1f : 0.5f);
        authButton.setOnClickListener(view -> beginGoogleSignIn());
        content.addView(authButton, matchWrap());
        addSpace(content, 19);
        TextView createAccount = text("New to InkMatch?  Create an account", 13, COLOR_MUTED, false);
        createAccount.setGravity(Gravity.CENTER);
        content.addView(createAccount, matchWrap());
        createAccount.setOnClickListener(view -> {
            screen = AppScreen.SIGN_UP;
            renderCurrentScreen();
        });
        setPage(page);
    }

    private void renderSignUp() {
        ScrollView page = createPage();
        content = createPageContent();
        page.addView(content);
        addBackHeader(content);
        addSpace(content, 28);
        content.addView(text("Create an Account", 27, COLOR_INK, true));
        addSpace(content, 7);
        content.addView(text("Join InkMatch to start comparing handwriting.", 14, COLOR_MUTED, false));
        addSpace(content, 22);
        nameInput = addInput(content, "Name", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PERSON_NAME);
        addSpace(content, 11);
        emailInput = addInput(content, "Email", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        addSpace(content, 11);
        passwordInput = addInput(content, "Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addSpace(content, 11);
        confirmPasswordInput = addInput(content, "Confirm password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addSpace(content, 17);
        TextView create = actionButton("Create Account", COLOR_GREEN, Color.WHITE);
        content.addView(create, matchWrap());
        create.setOnClickListener(view -> createEmailAccount());
        addAuthMessage();
        addSeparator(content);
        authButton = googleSignInButton();
        authButton.setEnabled(authReady);
        authButton.setAlpha(authReady ? 1f : 0.5f);
        authButton.setOnClickListener(view -> beginGoogleSignIn());
        content.addView(authButton, matchWrap());
        addSpace(content, 18);
        TextView signIn = text("Already have an account?  Sign in", 13, COLOR_MUTED, false);
        signIn.setGravity(Gravity.CENTER);
        content.addView(signIn, matchWrap());
        signIn.setOnClickListener(view -> {
            screen = AppScreen.SIGN_IN;
            renderCurrentScreen();
        });
        setPage(page);
    }

    private void renderResetPassword() {
        ScrollView page = createPage();
        content = createPageContent();
        page.addView(content);
        addBackHeader(content);
        addSpace(content, 37);
        content.addView(text("Forgot Password", 27, COLOR_INK, true));
        addSpace(content, 10);
        content.addView(text(
                "Enter your registered email address and we’ll send you a link to reset your password.",
                14, COLOR_MUTED, false));
        addSpace(content, 24);
        emailInput = addInput(content, "Email", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        addSpace(content, 18);
        TextView send = actionButton("Send Reset Link", COLOR_GREEN, Color.WHITE);
        content.addView(send, matchWrap());
        send.setOnClickListener(view -> sendPasswordReset());
        addAuthMessage();
        setPage(page);
    }

    private void addAuthHeader(LinearLayout parent) {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER);
        TextView mark = text("✎", 34, COLOR_GREEN, true);
        mark.setGravity(Gravity.CENTER);
        header.addView(mark, new LinearLayout.LayoutParams(-1, dp(61)));
        parent.addView(header);
        TextView brand = text("InkMatch", 29, COLOR_INK, true);
        brand.setGravity(Gravity.CENTER);
        parent.addView(brand, matchWrap());
        addSpace(parent, 5);
        TextView subtitle = text("Compare handwriting. Find the match.", 13, COLOR_MUTED, false);
        subtitle.setGravity(Gravity.CENTER);
        parent.addView(subtitle, matchWrap());
    }

    private void addBackHeader(LinearLayout parent) {
        addBackHeader(parent, AppScreen.SIGN_IN);
    }

    private void addBackHeader(LinearLayout parent, AppScreen destination) {
        TextView back = text("‹    InkMatch", 17, COLOR_INK, true);
        parent.addView(back, matchWrap());
        back.setOnClickListener(view -> {
            screen = destination;
            renderCurrentScreen();
        });
    }

    private EditText addInput(LinearLayout parent, String hint, int inputType) {
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setTextSize(14);
        input.setTextColor(COLOR_INK);
        input.setHintTextColor(COLOR_MUTED);
        input.setHint(hint);
        input.setInputType(inputType);
        input.setPadding(dp(15), 0, dp(15), 0);
        input.setBackground(rounded(Color.WHITE, 10, COLOR_BORDER));
        parent.addView(input, new LinearLayout.LayoutParams(-1, dp(50)));
        return input;
    }

    private void addAuthMessage() {
        authMessage = text("", 12, COLOR_ERROR, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(8);
        content.addView(authMessage, params);
    }

    private void addSeparator(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        addSpace(parent, 15);
        View left = new View(this);
        left.setBackgroundColor(COLOR_BORDER);
        row.addView(left, new LinearLayout.LayoutParams(0, dp(1), 1f));
        TextView or = text("   or   ", 12, COLOR_MUTED, false);
        row.addView(or, wrap());
        View right = new View(this);
        right.setBackgroundColor(COLOR_BORDER);
        row.addView(right, new LinearLayout.LayoutParams(0, dp(1), 1f));
        parent.addView(row, matchWrap());
        addSpace(parent, 15);
    }

    private void signInWithEmail() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        if (firebaseAuth == null) {
            setAuthMessage("Firebase authentication is not configured. Check the Android Firebase setup.");
            return;
        }
        if (email.isEmpty() || password.isEmpty()) {
            setAuthMessage("Enter your email address and password.");
            return;
        }
        setAuthMessage("Signing in…", COLOR_MUTED);
        firebaseAuth.signInWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        screen = AppScreen.COMPARE;
                        return;
                    }
                    android.util.Log.e("Inkprint", "Email sign-in failed.", task.getException());
                    setAuthMessage(emailAuthError(task.getException(), "sign in"));
                });
    }

    private void createEmailAccount() {
        String name = nameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String confirmation = confirmPasswordInput.getText().toString();
        if (firebaseAuth == null) {
            setAuthMessage("Firebase authentication is not configured. Check the Android Firebase setup.");
            return;
        }
        if (name.isEmpty() || email.isEmpty() || password.isEmpty() || confirmation.isEmpty()) {
            setAuthMessage("Complete all fields to create your account.");
            return;
        }
        if (password.length() < 6) {
            setAuthMessage("Use a password with at least 6 characters.");
            return;
        }
        if (!password.equals(confirmation)) {
            setAuthMessage("The passwords do not match.");
            return;
        }
        setAuthMessage("Creating your account…", COLOR_MUTED);
        firebaseAuth.createUserWithEmailAndPassword(email, password)
                .addOnCompleteListener(this, task -> {
                    if (!task.isSuccessful()) {
                        android.util.Log.e("Inkprint", "Email account creation failed.", task.getException());
                        setAuthMessage(emailAuthError(task.getException(), "create an account"));
                        return;
                    }
                    FirebaseUser user = firebaseAuth.getCurrentUser();
                    if (user == null) {
                        setAuthMessage("Your account was created, but sign-in could not be completed.");
                        return;
                    }
                    UserProfileChangeRequest profile = new UserProfileChangeRequest.Builder()
                            .setDisplayName(name)
                            .build();
                    user.updateProfile(profile).addOnCompleteListener(this, profileTask -> {
                        if (!profileTask.isSuccessful()) {
                            android.util.Log.e("Inkprint", "Account name could not be saved.", profileTask.getException());
                            showToast("Account created. You can update your name from Profile.");
                        }
                        screen = AppScreen.COMPARE;
                        renderCurrentScreen();
                    });
                });
    }

    private void sendPasswordReset() {
        String email = emailInput.getText().toString().trim();
        if (firebaseAuth == null) {
            setAuthMessage("Firebase authentication is not configured. Check the Android Firebase setup.");
            return;
        }
        if (email.isEmpty()) {
            setAuthMessage("Enter the email address registered to your account.");
            return;
        }
        setAuthMessage("Sending reset link…", COLOR_MUTED);
        firebaseAuth.sendPasswordResetEmail(email).addOnCompleteListener(this, task -> {
            if (task.isSuccessful()) {
                setAuthMessage("If an account matches that address, a password reset link has been sent.", COLOR_GREEN);
                return;
            }
            android.util.Log.e("Inkprint", "Password reset email could not be sent.", task.getException());
            String message = emailAuthError(task.getException(), "send a reset link");
            if (message.contains("No account")) {
                message = "If an account matches that address, a password reset link has been sent.";
                setAuthMessage(message, COLOR_GREEN);
            } else {
                setAuthMessage(message);
            }
        });
    }

    private void setAuthMessage(String message) {
        setAuthMessage(message, COLOR_ERROR);
    }

    private void setAuthMessage(String message, int color) {
        if (authMessage == null) return;
        authMessage.setTextColor(color);
        authMessage.setText(message);
    }

    private String emailAuthError(@Nullable Exception exception, String action) {
        String detail = exception == null ? "" : String.valueOf(exception.getMessage());
        if (detail.contains("CONFIGURATION_NOT_FOUND")
                || detail.contains("operation is not allowed")) {
            return "Email/password sign-in is disabled. Enable it in Firebase Authentication.";
        }
        if (detail.contains("invalid-email")) return "Enter a valid email address.";
        if (detail.contains("user-not-found") || detail.contains("no user record")) {
            return "No account matches that email address.";
        }
        if (detail.contains("wrong-password") || detail.contains("invalid-credential")) {
            return "The email or password is incorrect.";
        }
        if (detail.contains("email-already-in-use")) return "An account already uses that email.";
        if (detail.contains("weak-password")) return "Choose a stronger password with at least 6 characters.";
        if (detail.contains("network")) return "Check your internet connection and try again.";
        return "Couldn't " + action + ". Check your details and try again.";
    }

    private View googleSignInButton() {
        LinearLayout button = new LinearLayout(this);
        button.setGravity(Gravity.CENTER);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setBackground(rounded(Color.WHITE, 12, COLOR_BORDER));
        button.setClickable(true);
        button.setFocusable(true);
        button.setMinimumHeight(dp(54));
        button.setContentDescription("Continue with Google");

        TextView googleMark = text("G", 19, Color.rgb(66, 133, 244), true);
        googleMark.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        button.addView(googleMark);
        TextView label = text("Continue with Google", 15, COLOR_INK, true);
        LinearLayout.LayoutParams labelParams = wrap();
        labelParams.leftMargin = dp(12);
        button.addView(label, labelParams);
        return button;
    }

    private void beginGoogleSignIn() {
        if (!authReady || firebaseAuth == null) {
            authMessage.setText(authError.isEmpty()
                    ? "Google sign-in isn't configured yet."
                    : authError);
            return;
        }
        authButton.setEnabled(false);
        authButton.setAlpha(0.65f);
        authMessage.setTextColor(COLOR_MUTED);
        authMessage.setText("Opening Google sign-in…");

        GetGoogleIdOption googleOption = new GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(BuildConfig.GOOGLE_WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build();
        GetCredentialRequest request = new GetCredentialRequest.Builder()
                .addCredentialOption(googleOption)
                .build();

        credentialManager.getCredentialAsync(
                this,
                request,
                null,
                getMainExecutor(),
                new androidx.credentials.CredentialManagerCallback<
                        androidx.credentials.GetCredentialResponse, GetCredentialException>() {
                    @Override
                    public void onResult(androidx.credentials.GetCredentialResponse response) {
                        signInWithGoogleCredential(response.getCredential());
                    }

                    @Override
                    public void onError(GetCredentialException exception) {
                        authButton.setEnabled(true);
                        authButton.setAlpha(1f);
                        if (exception instanceof GetCredentialCancellationException) {
                            authMessage.setText("");
                            return;
                        }
                        android.util.Log.e("Inkprint", "Google sign-in could not obtain a credential.", exception);
                        authMessage.setTextColor(COLOR_ERROR);
                        authMessage.setText(credentialErrorMessage(exception));
                    }
                });
    }

    private String credentialErrorMessage(GetCredentialException exception) {
        if (exception.getType().contains("NoCredential")) {
            return "No Google account is available. Add a Google account to your device and try again.";
        }
        return "Google sign-in couldn't start. Check your connection and Google Play services, then try again.";
    }

    private void signInWithGoogleCredential(Credential credential) {
        if (!(credential instanceof CustomCredential)
                || !GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL.equals(credential.getType())) {
            authButton.setEnabled(true);
            authButton.setAlpha(1f);
            authMessage.setTextColor(COLOR_ERROR);
            authMessage.setText("Google returned a sign-in credential that Inkprint doesn't recognize.");
            return;
        }
        GoogleIdTokenCredential googleCredential =
                GoogleIdTokenCredential.createFrom(((CustomCredential) credential).getData());
        AuthCredential firebaseCredential =
                GoogleAuthProvider.getCredential(googleCredential.getIdToken(), null);
        firebaseAuth.signInWithCredential(firebaseCredential)
                .addOnCompleteListener(this, task -> {
                    if (task.isSuccessful()) {
                        authError = "";
                        return;
                    }
                    authButton.setEnabled(true);
                    authButton.setAlpha(1f);
                    authMessage.setTextColor(COLOR_ERROR);
                    android.util.Log.e("Inkprint", "Firebase Google sign-in failed.", task.getException());
                    authMessage.setText(firebaseSignInError(task.getException()));
                });
    }

    private String firebaseSignInError(@Nullable Exception exception) {
        if (exception == null) return "Google sign-in failed. Please try again.";
        String detail = exception.getMessage();
        if (detail != null && (detail.contains("CONFIGURATION_NOT_FOUND")
                || detail.contains("operation is not allowed"))) {
            return "Google sign-in is disabled for this Firebase project. Enable the Google provider in Firebase Authentication.";
        }
        if (detail != null && detail.contains("API key not valid")) {
            return "Firebase rejected this app's API key. Check your Firebase Android configuration.";
        }
        if (detail != null && detail.contains("network")) {
            return "Couldn't reach Firebase. Check your internet connection and try again.";
        }
        return "Couldn't finish Google sign-in. Check that Google sign-in is enabled in Firebase and try again.";
    }

    private void renderComparisonApp(FirebaseUser user) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        ScrollView page = createPage();
        content = createPageContent();
        page.addView(content);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(createBottomNavigation(AppScreen.COMPARE));
        content.addView(createSignedInHeader());
        addSpace(content, 29);

        content.addView(text("COMPARE HANDWRITING", 10, COLOR_GREEN, true));
        addSpace(content, 11);
        TextView title = text("Compare the writing.", 31, COLOR_INK, true);
        title.setLetterSpacing(-0.025f);
        content.addView(title);
        addSpace(content, 8);
        content.addView(text(
                "Choose two clear photos. Your images are analyzed privately on your phone.",
                14, COLOR_MUTED, false));
        addSpace(content, 22);

        LinearLayout sampleRow = new LinearLayout(this);
        sampleRow.setGravity(Gravity.CENTER_VERTICAL);
        addSampleCard(sampleRow, 0);
        addSampleCard(sampleRow, 1);
        content.addView(sampleRow, matchWrap());
        addSpace(content, 20);

        compareButton = actionButton("Compare handwriting", COLOR_GREEN, Color.WHITE);
        compareButton.setEnabled(samples[0] != null && samples[0].features != null
                && samples[1] != null && samples[1].features != null);
        compareButton.setAlpha(compareButton.isEnabled() ? 1f : 0.48f);
        compareButton.setOnClickListener(view -> compareSamples());
        content.addView(compareButton, matchWrap());
        addSpace(content, 10);
        comparisonMessage = text("", 12, COLOR_ERROR, false);
        comparisonMessage.setGravity(Gravity.CENTER);
        content.addView(comparisonMessage);
        addSpace(content, 4);

        if (comparison != null) {
            addComparisonResult();
        } else {
            content.addView(text(
                    "A visual estimate, not proof of authorship. Different words and writing conditions can affect results.",
                    11, COLOR_MUTED, false));
        }
        addSpace(content, 25);
        setPage(root);
    }

    private void renderHistory(FirebaseUser user) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        ScrollView page = createPage();
        content = createPageContent();
        page.addView(content);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(createBottomNavigation(AppScreen.HISTORY));
        content.addView(createSignedInHeader());
        addSpace(content, 25);
        content.addView(text("Previous Comparisons", 24, COLOR_INK, true));
        addSpace(content, 5);
        content.addView(text("Your five most recent analyses, saved only on this device.", 13, COLOR_MUTED, false));
        addSpace(content, 15);

        try {
            List<AnalysisHistoryStore.Entry> entries =
                    new AnalysisHistoryStore(this, user.getUid()).getEntries();
            if (entries.isEmpty()) {
                LinearLayout empty = card(18);
                empty.addView(text("No comparisons yet", 17, COLOR_INK, true));
                addSpace(empty, 7);
                empty.addView(text("Your completed analyses will appear here.", 13, COLOR_MUTED, false));
                content.addView(empty, matchWrap());
            } else {
                AnalysisHistoryStore store = new AnalysisHistoryStore(this, user.getUid());
                for (AnalysisHistoryStore.Entry entry : entries) {
                    addHistoryEntry(store, entry);
                    addSpace(content, 10);
                }
            }
        } catch (IOException exception) {
            android.util.Log.e("Inkprint", "Local comparison history could not be loaded.", exception);
            content.addView(text("History couldn't be loaded. Please try again.", 13, COLOR_ERROR, false));
        }
        setPage(root);
    }

    private void addHistoryEntry(AnalysisHistoryStore store, AnalysisHistoryStore.Entry entry) {
        LinearLayout row = card(10);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout previews = new LinearLayout(this);
        previews.setGravity(Gravity.CENTER_VERTICAL);
        ImageView first = historyThumbnail(store.loadThumbnail(entry.firstThumbnail), "First sample thumbnail");
        ImageView second = historyThumbnail(store.loadThumbnail(entry.secondThumbnail), "Second sample thumbnail");
        previews.addView(first, new LinearLayout.LayoutParams(dp(54), dp(54)));
        LinearLayout.LayoutParams secondParams = new LinearLayout.LayoutParams(dp(54), dp(54));
        secondParams.leftMargin = dp(4);
        previews.addView(second, secondParams);
        row.addView(previews, wrap());

        LinearLayout details = column();
        LinearLayout.LayoutParams detailsParams = new LinearLayout.LayoutParams(0, -2, 1f);
        detailsParams.leftMargin = dp(9);
        row.addView(details, detailsParams);
        TextView score = text(Math.round(entry.score) + "%", 18, scoreColor(entry.score), true);
        details.addView(score);
        details.addView(text(historyResultTitle(entry.score), 11, scoreColor(entry.score), true));
        details.addView(text(AnalysisHistoryStore.formatDate(entry.createdAt), 10, COLOR_MUTED, false));

        TextView delete = text("×", 22, COLOR_MUTED, false);
        delete.setGravity(Gravity.CENTER);
        delete.setContentDescription("Delete this comparison from history");
        row.addView(delete, new LinearLayout.LayoutParams(dp(32), dp(45)));
        row.setClickable(true);
        row.setOnClickListener(view -> showHistoryDetails(entry));
        delete.setOnClickListener(view -> {
            try {
                store.delete(entry.id);
                renderCurrentScreen();
            } catch (IOException exception) {
                android.util.Log.e("Inkprint", "Comparison history entry could not be deleted.", exception);
                showToast("Couldn't delete this comparison from history.");
            }
        });
        content.addView(row, matchWrap());
    }

    private void showHistoryDetails(AnalysisHistoryStore.Entry entry) {
        String details = entry.firstName + " vs " + entry.secondName
                + "\n" + AnalysisHistoryStore.formatDate(entry.createdAt)
                + "\n\nOverall shape patterns: " + Math.round(entry.shape * 100f) + "%"
                + "\nInk & stroke weight: " + Math.round(entry.weight * 100f) + "%"
                + "\nWriting density: " + Math.round(entry.density * 100f) + "%"
                + "\nSample proportions: " + Math.round(entry.proportions * 100f) + "%"
                + "\n\nThis is a visual estimate, not proof of authorship.";
        new AlertDialog.Builder(this)
                .setTitle(Math.round(entry.score) + "% visual similarity")
                .setMessage(details)
                .setPositiveButton("Close", null)
                .show();
    }

    private ImageView historyThumbnail(@Nullable Bitmap bitmap, String description) {
        ImageView image = new ImageView(this);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackground(rounded(Color.rgb(245, 246, 243), 8, COLOR_BORDER));
        image.setContentDescription(description);
        image.setClipToOutline(true);
        if (bitmap != null) {
            image.setImageBitmap(bitmap);
            image.setTag(bitmap);
        }
        return image;
    }

    private void renderProfile(FirebaseUser user) {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BACKGROUND);
        ScrollView page = createPage();
        content = createPageContent();
        page.addView(content);
        root.addView(page, new LinearLayout.LayoutParams(-1, 0, 1f));
        root.addView(createBottomNavigation(AppScreen.PROFILE));
        content.addView(createSignedInHeader());
        addSpace(content, 25);
        content.addView(text("Profile", 24, COLOR_INK, true));
        addSpace(content, 18);

        String displayName = user.getDisplayName();
        String email = user.getEmail();
        TextView avatar = text(
                displayName == null || displayName.trim().isEmpty()
                        ? "?" : displayName.trim().substring(0, 1).toUpperCase(),
                27, COLOR_GREEN, true);
        avatar.setGravity(Gravity.CENTER);
        avatar.setBackground(rounded(Color.rgb(237, 240, 255), 48, Color.TRANSPARENT));
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(70), dp(70));
        avatarParams.gravity = Gravity.CENTER_HORIZONTAL;
        content.addView(avatar, avatarParams);
        addSpace(content, 8);
        TextView name = text(
                displayName == null || displayName.trim().isEmpty() ? "Account" : displayName,
                18, COLOR_INK, true);
        name.setGravity(Gravity.CENTER);
        content.addView(name, matchWrap());
        TextView emailLabel = text(email == null ? "" : email, 12, COLOR_MUTED, false);
        emailLabel.setGravity(Gravity.CENTER);
        content.addView(emailLabel, matchWrap());
        addSpace(content, 19);

        LinearLayout accountCard = card(0);
        accountCard.addView(profileRow("Name", displayName == null ? "Account" : displayName));
        accountCard.addView(profileRow("Registered email", email == null ? "" : email));
        TextView change = profileAction("Change Password");
        accountCard.addView(change, matchWrap());
        change.setOnClickListener(view -> {
            screen = AppScreen.CHANGE_PASSWORD;
            renderCurrentScreen();
        });
        TextView signOut = profileAction("Sign Out");
        signOut.setTextColor(COLOR_ERROR);
        accountCard.addView(signOut, matchWrap());
        signOut.setOnClickListener(view -> signOut());
        content.addView(accountCard, matchWrap());
        addSpace(content, 20);
        content.addView(text("Your handwriting images stay on this device.", 12, COLOR_MUTED, false));
        setPage(root);
    }

    private void renderChangePassword(FirebaseUser user) {
        ScrollView page = createPage();
        content = createPageContent();
        page.addView(content);
        addBackHeader(content, AppScreen.PROFILE);
        addSpace(content, 30);
        content.addView(text("Change Password", 26, COLOR_INK, true));
        addSpace(content, 18);
        boolean emailAccount = false;
        for (com.google.firebase.auth.UserInfo provider : user.getProviderData()) {
            if ("password".equals(provider.getProviderId())) emailAccount = true;
        }
        if (!emailAccount) {
            content.addView(text(
                    "This account uses Google sign-in and has no InkMatch password to change. Use your Google account settings to manage its password.",
                    14, COLOR_MUTED, false));
            setPage(page);
            return;
        }
        currentPasswordInput = addInput(content, "Current Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addSpace(content, 11);
        newPasswordInput = addInput(content, "New Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addSpace(content, 11);
        confirmPasswordInput = addInput(content, "Confirm New Password", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        addSpace(content, 18);
        TextView update = actionButton("Update Password", COLOR_GREEN, Color.WHITE);
        content.addView(update, matchWrap());
        update.setOnClickListener(view -> updatePassword(user));
        addAuthMessage();
        setPage(page);
    }

    private void updatePassword(FirebaseUser user) {
        String current = currentPasswordInput.getText().toString();
        String newPassword = newPasswordInput.getText().toString();
        String confirmation = confirmPasswordInput.getText().toString();
        if (current.isEmpty() || newPassword.isEmpty() || confirmation.isEmpty()) {
            setAuthMessage("Complete all password fields.");
            return;
        }
        if (newPassword.length() < 6) {
            setAuthMessage("Use a password with at least 6 characters.");
            return;
        }
        if (!newPassword.equals(confirmation)) {
            setAuthMessage("The new passwords do not match.");
            return;
        }
        String email = user.getEmail();
        if (email == null || email.isEmpty()) {
            setAuthMessage("This account does not have an email address for password verification.");
            return;
        }
        setAuthMessage("Updating password…", COLOR_MUTED);
        user.reauthenticate(EmailAuthProvider.getCredential(email, current))
                .addOnCompleteListener(this, reauthTask -> {
                    if (!reauthTask.isSuccessful()) {
                        android.util.Log.e("Inkprint", "Password change reauthentication failed.", reauthTask.getException());
                        setAuthMessage("Current password is incorrect. Try again.");
                        return;
                    }
                    user.updatePassword(newPassword).addOnCompleteListener(this, updateTask -> {
                        if (!updateTask.isSuccessful()) {
                            android.util.Log.e("Inkprint", "Password update failed.", updateTask.getException());
                            setAuthMessage(emailAuthError(updateTask.getException(), "update your password"));
                            return;
                        }
                        Toast.makeText(this, "Password updated.", Toast.LENGTH_LONG).show();
                        screen = AppScreen.PROFILE;
                        renderCurrentScreen();
                    });
                });
    }

    private LinearLayout profileRow(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(14), dp(11), dp(14), dp(11));
        row.addView(text(label, 11, COLOR_MUTED, false));
        row.addView(text(value, 13, COLOR_INK, true));
        row.setBackground(rounded(Color.WHITE, 9, COLOR_BORDER));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(7);
        row.setLayoutParams(params);
        return row;
    }

    private TextView profileAction(String label) {
        TextView action = text(label + "    ›", 14, COLOR_INK, true);
        action.setPadding(dp(14), dp(13), dp(14), dp(13));
        action.setBackground(rounded(Color.WHITE, 9, COLOR_BORDER));
        LinearLayout.LayoutParams params = matchWrap();
        params.bottomMargin = dp(7);
        action.setLayoutParams(params);
        return action;
    }

    private LinearLayout createBottomNavigation(AppScreen selected) {
        LinearLayout navigation = new LinearLayout(this);
        navigation.setGravity(Gravity.CENTER);
        navigation.setBackground(rounded(Color.WHITE, 12, COLOR_BORDER));
        navigation.setElevation(dp(5));
        navigation.setPadding(dp(7), dp(5), dp(7), dp(5));
        addNavigationItem(navigation, "⌂", "Compare", AppScreen.COMPARE, selected);
        addNavigationItem(navigation, "▤", "History", AppScreen.HISTORY, selected);
        addNavigationItem(navigation, "○", "Profile", AppScreen.PROFILE, selected);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(60));
        params.setMargins(dp(14), dp(4), dp(14), dp(8));
        navigation.setLayoutParams(params);
        return navigation;
    }

    private void addNavigationItem(
            LinearLayout navigation, String icon, String label, AppScreen destination, AppScreen selected) {
        LinearLayout item = column();
        item.setGravity(Gravity.CENTER);
        TextView iconView = text(icon, 18, destination == selected ? COLOR_GREEN : COLOR_MUTED, true);
        iconView.setGravity(Gravity.CENTER);
        TextView labelView = text(label, 10, destination == selected ? COLOR_GREEN : COLOR_MUTED, true);
        labelView.setGravity(Gravity.CENTER);
        item.addView(iconView, new LinearLayout.LayoutParams(-1, dp(26)));
        item.addView(labelView, new LinearLayout.LayoutParams(-1, dp(19)));
        navigation.addView(item, new LinearLayout.LayoutParams(0, -1, 1f));
        item.setContentDescription(label);
        item.setClickable(true);
        item.setFocusable(true);
        item.setOnClickListener(view -> {
            screen = destination;
            renderCurrentScreen();
        });
    }

    private int scoreColor(float score) {
        if (score >= 78f) return COLOR_MATCH;
        if (score >= 55f) return Color.rgb(214, 135, 32);
        return COLOR_ERROR;
    }

    private String historyResultTitle(float score) {
        if (score >= 78f) return "Likely same person";
        if (score >= 55f) return "Possibly same person";
        return "Different person";
    }

    private LinearLayout createSignedInHeader() {
        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView logo = text("InkMatch", 21, COLOR_INK, true);
        logo.setLetterSpacing(-0.04f);
        header.addView(logo, new LinearLayout.LayoutParams(0, dp(43), 1f));
        TextView profile = actionButton("○", Color.WHITE, COLOR_MUTED);
        profile.setTextSize(19);
        profile.setContentDescription("Open profile");
        header.addView(profile, wrap());
        profile.setOnClickListener(view -> {
            screen = AppScreen.PROFILE;
            renderCurrentScreen();
        });
        return header;
    }

    private void addSampleCard(LinearLayout parent, int index) {
        Sample sample = samples[index];
        LinearLayout sampleCard = card(15);
        LinearLayout heading = new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView step = text(index == 0 ? "01" : "02", 12, COLOR_GREEN, true);
        step.setGravity(Gravity.CENTER);
        step.setBackground(rounded(Color.rgb(237, 244, 237), 9, Color.TRANSPARENT));
        heading.addView(step, new LinearLayout.LayoutParams(dp(35), dp(35)));
        LinearLayout headingText = column();
        TextView sampleTitle = text(index == 0 ? "First sample" : "Second sample", 15, COLOR_INK, true);
        headingText.addView(sampleTitle);
        headingText.addView(text(
                index == 0 ? "Your reference handwriting" : "The handwriting to compare",
                12, COLOR_MUTED, false));
        LinearLayout.LayoutParams headingTextParams = new LinearLayout.LayoutParams(0, -2, 1f);
        headingTextParams.leftMargin = dp(11);
        heading.addView(headingText, headingTextParams);
        if (sample != null && sample.bitmap != null) {
            TextView remove = actionButton("Remove", Color.WHITE, COLOR_MUTED);
            remove.setTextSize(12);
            remove.setBackground(rounded(Color.WHITE, 9, COLOR_BORDER));
            heading.addView(remove, wrap());
            remove.setOnClickListener(view -> clearSample(index));
        }
        sampleCard.addView(heading);
        addSpace(sampleCard, 13);

        if (sample != null && sample.bitmap != null) {
            ImageView preview = new ImageView(this);
            preview.setImageBitmap(sample.bitmap);
            preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
            preview.setBackground(rounded(Color.rgb(245, 246, 243), 10, Color.TRANSPARENT));
            preview.setContentDescription((index == 0 ? "First" : "Second") + " handwriting sample preview");
            preview.setClipToOutline(true);
            sampleCard.addView(preview, new LinearLayout.LayoutParams(-1, dp(104)));
            TextView fileName = text(sample.displayName, 12, COLOR_MUTED, false);
            fileName.setSingleLine(true);
            fileName.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            addSpace(sampleCard, 7);
            sampleCard.addView(fileName);
        } else {
            TextView choose = actionButton(
                    sample != null && sample.loading ? "Preparing photo…" : "Choose a photo",
                    Color.rgb(248, 250, 247), COLOR_GREEN);
            choose.setGravity(Gravity.CENTER);
            choose.setBackground(rounded(Color.rgb(248, 250, 247), 10, COLOR_BORDER));
            choose.setContentDescription((index == 0 ? "Choose first" : "Choose second") + " handwriting sample photo");
            choose.setEnabled(sample == null || !sample.loading);
            choose.setAlpha(choose.isEnabled() ? 1f : 0.6f);
            sampleCard.addView(choose, new LinearLayout.LayoutParams(-1, dp(106)));
            choose.setOnClickListener(view -> pickImage(index));
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -2, 1f);
        params.setMargins(dp(index == 0 ? 0 : 5), 0, dp(index == 0 ? 5 : 0), 0);
        parent.addView(sampleCard, params);
    }

    private void addComparisonResult() {
        HandwritingAnalyzer.Comparison result = comparison;
        LinearLayout resultCard = card(18);
        addSpace(content, 15);

        LinearLayout scoreLayout = new LinearLayout(this);
        scoreLayout.setGravity(Gravity.CENTER_VERTICAL);
        ScoreView scoreView = new ScoreView(this, result.score);
        scoreLayout.addView(scoreView, new LinearLayout.LayoutParams(dp(112), dp(112)));
        LinearLayout scoreText = column();
        scoreText.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams scoreTextParams = new LinearLayout.LayoutParams(0, -2, 1f);
        scoreTextParams.leftMargin = dp(19);
        scoreLayout.addView(scoreText, scoreTextParams);
        scoreText.addView(text("VISUAL SIMILARITY", 10, COLOR_GREEN, true));
        addSpace(scoreText, 6);
        String title;
        String description;
        if (result.score >= 78f) {
            title = "Many traits look alike";
            description = "The samples share several broad visual patterns.";
        } else if (result.score >= 55f) {
            title = "Some traits look alike";
            description = "The samples show a mix of similar and different patterns.";
        } else {
            title = "Few traits look alike";
            description = "The samples differ across several broad visual patterns.";
        }
        scoreText.addView(text(title, 18, COLOR_INK, true));
        addSpace(scoreText, 5);
        scoreText.addView(text(description, 12, COLOR_MUTED, false));
        resultCard.addView(scoreLayout);

        View separator = new View(this);
        separator.setBackgroundColor(COLOR_BORDER);
        LinearLayout.LayoutParams separatorParams = new LinearLayout.LayoutParams(-1, dp(1));
        separatorParams.topMargin = dp(17);
        separatorParams.bottomMargin = dp(16);
        resultCard.addView(separator, separatorParams);
        resultCard.addView(text("What we compared", 14, COLOR_INK, true));
        addSpace(resultCard, 11);
        addTrait(resultCard, "Overall shape patterns", result.shape);
        addTrait(resultCard, "Ink & stroke weight", result.weight);
        addTrait(resultCard, "Writing density", result.density);
        addTrait(resultCard, "Sample proportions", result.proportions);

        TextView disclaimer = text(
                "This experimental estimate is not a calibrated probability or forensic conclusion. Different words, image quality and writing conditions can affect the result.",
                11, COLOR_MUTED, false);
        disclaimer.setBackground(rounded(Color.rgb(247, 248, 245), 9, Color.TRANSPARENT));
        disclaimer.setPadding(dp(11), dp(11), dp(11), dp(11));
        LinearLayout.LayoutParams disclaimerParams = new LinearLayout.LayoutParams(-1, -2);
        disclaimerParams.topMargin = dp(9);
        resultCard.addView(disclaimer, disclaimerParams);

        TextView reset = actionButton("Start over", Color.WHITE, COLOR_GREEN);
        reset.setBackground(rounded(Color.WHITE, 9, COLOR_BORDER));
        LinearLayout.LayoutParams resetParams = matchWrap();
        resetParams.topMargin = dp(10);
        resultCard.addView(reset, resetParams);
        reset.setOnClickListener(view -> {
            comparison = null;
            clearSample(0);
            clearSample(1);
        });
        content.addView(resultCard);
    }

    private void addTrait(LinearLayout parent, String label, float value) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(label, 11, COLOR_MUTED, false);
        row.addView(name, new LinearLayout.LayoutParams(0, -2, 1f));
        int percent = Math.round(Math.max(0f, Math.min(1f, value)) * 100f);
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(percent);
        progress.setProgressTintList(ColorStateList.valueOf(COLOR_MATCH));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(COLOR_BORDER));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(0, dp(5), 1.25f);
        progressParams.setMargins(dp(8), dp(11), dp(8), dp(11));
        row.addView(progress, progressParams);
        TextView percentage = text(percent + "%", 12, COLOR_INK, true);
        percentage.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        row.addView(percentage, new LinearLayout.LayoutParams(dp(36), -2));
        parent.addView(row, matchWrap());
    }

    private void compareSamples() {
        if (samples[0] == null || samples[0].features == null
                || samples[1] == null || samples[1].features == null) {
            return;
        }
        comparison = HandwritingAnalyzer.compare(samples[0].features, samples[1].features);
        screen = AppScreen.COMPARE;
        FirebaseUser user = firebaseAuth == null ? null : firebaseAuth.getCurrentUser();
        if (user != null) {
            try {
                new AnalysisHistoryStore(this, user.getUid()).add(
                        comparison,
                        samples[0].displayName,
                        samples[1].displayName,
                        samples[0].bitmap,
                        samples[1].bitmap);
            } catch (IOException exception) {
                android.util.Log.e("Inkprint", "Completed comparison could not be saved to history.", exception);
                showToast("Comparison complete, but it couldn't be saved to history.");
            }
        }
        renderCurrentScreen();
    }

    private void pickImage(int index) {
        PickVisualMediaRequest request =
                new PickVisualMediaRequest.Builder().setMediaType(ActivityResultContracts.PickVisualMedia.ImageOnly.INSTANCE).build();
        if (index == 0) {
            firstImagePicker.launch(request);
        } else {
            secondImagePicker.launch(request);
        }
    }

    private void onImagePicked(int index, @Nullable Uri uri) {
        if (uri == null) return;
        comparison = null;
        Sample previous = samples[index];
        if (previous != null && previous.bitmap != null && !previous.bitmap.isRecycled()) {
            previous.bitmap.recycle();
        }
        Sample selected = new Sample(uri);
        selected.loading = true;
        samples[index] = selected;
        renderCurrentScreen();
        imageExecutor.execute(() -> {
            try {
                Bitmap bitmap = decodeImage(uri);
                HandwritingAnalyzer.Features features = HandwritingAnalyzer.analyze(bitmap);
                String name = imageName(uri);
                runOnUiThread(() -> {
                    if (isFinishing() || samples[index] != selected) {
                        bitmap.recycle();
                        return;
                    }
                    selected.bitmap = bitmap;
                    selected.features = features;
                    selected.displayName = name;
                    selected.loading = false;
                    renderCurrentScreen();
                });
            } catch (Exception exception) {
                android.util.Log.e("Inkprint", "Could not prepare selected handwriting image.", exception);
                runOnUiThread(() -> {
                    if (isFinishing() || samples[index] != selected) return;
                    samples[index] = null;
                    renderCurrentScreen();
                    showToast(exception instanceof IllegalArgumentException
                            ? exception.getMessage()
                            : "Couldn't read that photo. Choose another image.");
                });
            }
        });
    }

    private Bitmap decodeImage(Uri uri) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            ImageDecoder.Source source = ImageDecoder.createSource(getContentResolver(), uri);
            return ImageDecoder.decodeBitmap(source, (decoder, info, imageSource) -> {
                int width = info.getSize().getWidth();
                int height = info.getSize().getHeight();
                float scale = Math.min(1f, MAX_IMAGE_SIZE / Math.max(width, height));
                decoder.setTargetSize(
                        Math.max(1, Math.round(width * scale)),
                        Math.max(1, Math.round(height * scale)));
                decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
            });
        }

        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("The selected photo could not be opened.");
            BitmapFactory.decodeStream(stream, null, bounds);
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException("The selected file isn't a supported photo.");
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = Math.max(1, (int) Math.ceil(
                Math.max(bounds.outWidth, bounds.outHeight) / MAX_IMAGE_SIZE));
        Bitmap decoded;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException("The selected photo could not be opened.");
            decoded = BitmapFactory.decodeStream(stream, null, options);
        }
        if (decoded == null) throw new IOException("The selected file isn't a supported photo.");

        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            if (stream == null) return decoded;
            int orientation = new ExifInterface(stream).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            int degrees = orientation == ExifInterface.ORIENTATION_ROTATE_90 ? 90
                    : orientation == ExifInterface.ORIENTATION_ROTATE_180 ? 180
                    : orientation == ExifInterface.ORIENTATION_ROTATE_270 ? 270 : 0;
            if (degrees == 0) return decoded;
            Matrix matrix = new Matrix();
            matrix.postRotate(degrees);
            Bitmap rotated = Bitmap.createBitmap(
                    decoded, 0, 0, decoded.getWidth(), decoded.getHeight(), matrix, true);
            if (rotated != decoded) decoded.recycle();
            return rotated;
        }
    }

    private String imageName(Uri uri) {
        try (android.database.Cursor cursor = getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    String name = cursor.getString(index);
                    if (name != null && !name.isEmpty()) return name;
                }
            }
        } catch (Exception exception) {
            android.util.Log.w("Inkprint", "The selected image name isn't available.", exception);
        }
        return "Handwriting sample";
    }

    private void clearSample(int index) {
        Sample old = samples[index];
        samples[index] = null;
        if (old != null && old.bitmap != null && !old.bitmap.isRecycled()) old.bitmap.recycle();
        comparison = null;
        renderCurrentScreen();
    }

    private void signOut() {
        firebaseAuth.signOut();
        credentialManager.clearCredentialStateAsync(
                new ClearCredentialStateRequest(),
                null,
                getMainExecutor(),
                new androidx.credentials.CredentialManagerCallback<Void, ClearCredentialException>() {
                    @Override
                    public void onResult(Void result) {}

                    @Override
                    public void onError(ClearCredentialException exception) {
                        android.util.Log.w("Inkprint", "Google's saved credential state could not be cleared.", exception);
                    }
                });
    }

    private void restoreSamples(@Nullable Bundle savedInstanceState) {
        if (savedInstanceState == null) return;
        for (int i = 0; i < samples.length; i++) {
            String savedUri = savedInstanceState.getString("sample_uri_" + i);
            if (savedUri != null) onImagePicked(i, Uri.parse(savedUri));
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] != null) outState.putString("sample_uri_" + i, samples[i].uri.toString());
        }
        super.onSaveInstanceState(outState);
    }

    private ScrollView createPage() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(COLOR_BACKGROUND);
        scroll.setVerticalScrollBarEnabled(false);
        return scroll;
    }

    private LinearLayout createPageContent() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(23), dp(16), dp(23), dp(27));
        root.setFocusableInTouchMode(true);
        return root;
    }

    private void setPage(View page) {
        ViewCompat.setOnApplyWindowInsetsListener(page, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars());
            view.setPadding(view.getPaddingLeft(), dp(12) + bars.top, view.getPaddingRight(),
                    dp(19) + bars.bottom);
            return windowInsets;
        });
        setContentView(page);
        ViewCompat.requestApplyInsets(page);
    }

    private LinearLayout card(int padding) {
        LinearLayout result = column();
        result.setPadding(dp(padding), dp(padding), dp(padding), dp(padding));
        result.setBackground(rounded(COLOR_CARD, 17, COLOR_BORDER));
        result.setElevation(dp(2));
        return result;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView actionButton(String label, int background, int foreground) {
        TextView button = text(label, 15, foreground, true);
        button.setGravity(Gravity.CENTER);
        button.setMinHeight(dp(49));
        button.setPadding(dp(15), dp(10), dp(15), dp(10));
        button.setBackground(rounded(background, 11, Color.TRANSPARENT));
        button.setClickable(true);
        button.setFocusable(true);
        return button;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(Typeface.create(
                bold ? "sans-serif-medium" : "sans-serif",
                bold ? Typeface.BOLD : Typeface.NORMAL));
        view.setLineSpacing(dp(3), 1f);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }

    private GradientDrawable rounded(int color, int radius, int strokeColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        if (strokeColor != Color.TRANSPARENT) drawable.setStroke(dp(1), strokeColor);
        return drawable;
    }

    private void addSpace(LinearLayout parent, int height) {
        View spacer = new View(this);
        parent.addView(spacer, new LinearLayout.LayoutParams(1, dp(height)));
    }

    private LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void showToast(@Nullable String message) {
        Toast.makeText(this,
                message == null || message.isEmpty() ? "Something went wrong. Please try again." : message,
                Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onDestroy() {
        if (firebaseAuth != null && authStateListener != null) {
            firebaseAuth.removeAuthStateListener(authStateListener);
        }
        imageExecutor.shutdownNow();
        super.onDestroy();
    }

    private static final class Sample {
        final Uri uri;
        Bitmap bitmap;
        HandwritingAnalyzer.Features features;
        String displayName = "";
        boolean loading;

        Sample(Uri uri) {
            this.uri = uri;
        }
    }

    private enum AppScreen {
        SIGN_IN,
        SIGN_UP,
        RESET_PASSWORD,
        COMPARE,
        HISTORY,
        PROFILE,
        CHANGE_PASSWORD
    }

    private final class ScoreView extends View {
        private final float score;
        private final android.graphics.Paint paint =
                new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        ScoreView(Context context, float score) {
            super(context);
            this.score = score;
            setContentDescription("Visual match " + Math.round(score) + " percent");
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            super.onDraw(canvas);
            float centerX = getWidth() / 2f;
            float centerY = getHeight() / 2f;
            float radius = Math.min(centerX, centerY) - dp(9);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(dp(8));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setColor(COLOR_BORDER);
            canvas.drawCircle(centerX, centerY, radius, paint);
            paint.setColor(COLOR_MATCH);
            canvas.drawArc(centerX - radius, centerY - radius, centerX + radius, centerY + radius,
                    -90f, 360f * Math.max(0f, Math.min(score, 100f)) / 100f, false, paint);
            paint.setStyle(android.graphics.Paint.Style.FILL);
            paint.setTextAlign(android.graphics.Paint.Align.CENTER);
            paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
            paint.setColor(COLOR_INK);
            paint.setTextSize(dp(30));
            float textY = centerY - (paint.ascent() + paint.descent()) / 2f;
            canvas.drawText(String.valueOf(Math.round(score)), centerX, textY, paint);
            paint.setColor(COLOR_MUTED);
            paint.setTextSize(dp(10));
            canvas.drawText("visual match", centerX, centerY + dp(27), paint);
        }
    }
}
