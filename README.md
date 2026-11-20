# Epimetheus Kotlin for Melodica

The Android 8+ Melodica edition plays your Pandora stations through the native Android media player, with a media notification and lock-screen controls. Sign in with your enabled Melodica account and choose a station. Enable **Save listens to Spaceghost Scrobbles** to record eligible songs at https://spacegho.st/scrobble/. The account credentials stay in memory for the session.

Build the maintained radio edition from melodica/android using Gradle 8.13, JDK 17, Android SDK 36 and NDK 27.0.12077973. Its native station requests use the bundled Melodica source. The earlier Kotlin app, layouts and library history are retained. This edition ships station playback; the archived browse and Wear screens belong to the earlier app.

# Epimetheus
*The open source Pandora Music app for Android.*



Epimetheus is an open-source Pandora Music client for Android. The app uses [libepimetheus](https://github.com/EpimetheusAndroid/libepimetheus) to connect to Pandora. Both the app and the library are currently in development, and pull requests and issues are welcome.

## This version of the app is abandoned. I'm rewriting it with Flutter.

#### Implemented features

- View stations
- Play stations
- Feedback
- Android wear integration
- Android auto integration (not tested; but should work)
- [Portaller](http://portaller.com/) support, to access Pandora outside the US.

#### To-do

- Browse UI
- View song details
- Manage stations (create, delete, add music, etc.)
- Manage audio focus
- Build a settings page (audio format, art resolution, portaller, etc.)
- Register screen
