# InkMatch

InkMatch is a native Android app for comparing broad visual traits in two
handwriting samples. The Android app supports email/password and Google
sign-in, on-device comparison history, and profile management. Images are
selected with Android's system photo picker and analyzed locally; handwriting
images are never uploaded.

## Configure Firebase authentication

1. Register the Android app with application ID `com.inkprint.app` in your
   Firebase project.
2. Add the SHA-1 fingerprint for the release signing key to the Android app's
   Firebase settings: `3D:C3:A9:5C:E0:68:03:74:33:FA:56:3E:9D:1A:53:93:7B:8E:69:B8`.
3. Enable **Google** and **Email/Password** as providers in Firebase
   Authentication.
4. Create or select a **Web application** OAuth client for Firebase Auth, then
   download an updated `google-services.json`. It must include this Android
   app and the Web OAuth client.
5. Copy `.env.example` to `.env` and set `GOOGLE_WEB_CLIENT_ID` to that Web
   application's client ID. Do not use an OAuth Desktop/installed-app client.
6. Keep `.env`, `google-services.json`, OAuth client-secret files, and signing
   keys private. These local configuration files are excluded from Git. A
   Google OAuth client secret is not used in the app and must never be shipped
   in an APK.

The Android release build checks that the Web OAuth client ID matches the
client registered in `google-services.json`, and fails rather than shipping a
release with a broken Google sign-in configuration.

## Build the native Android app

Install Node.js 22 or later, a Java 21 JDK, and Android SDK Platform 36 with
Build Tools 36 or later. Set `JAVA_HOME` and `ANDROID_HOME`, configure Firebase
as above, and run:

1. `npm install`
2. Configure `android/keystore.properties` for the local release signing key.
3. `npm run android:release`

The installable, signed release APK is written to
`android/app/build/outputs/apk/release/app-release.apk`. Android release
version `2.0.0` uses version code `3` so it can update the earlier published
APK. The signing key must remain the same for users to install updates.

Run `npm run android:test` with an Android emulator or device connected to run
the native UI, handwriting-comparison, and local-history instrumentation tests.
Password recovery sends Firebase's reset link to the supplied email address.
The app does not store account passwords. Each account's five most recent
comparison scores, trait values, and small JPEG thumbnails are stored in that
account's private app storage on the device; original images are not copied
into history. Deleting a history entry also deletes its thumbnails.

## Windows desktop app

The separate desktop build still runs from the existing Electron shell with
`npm start`; build its installer and portable executable with
`npm run package:win`. Desktop package version is `2.0.0`.

Android requests internet access for Firebase Authentication. Image selection
and handwriting comparison remain local to the device.
