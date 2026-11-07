# GoStream — Android Studio project

GoStream is an early Android prototype for receiving a GoPro HERO4 Silver live feed over the camera's Wi-Fi and forwarding it to a custom RTMP/RTMPS destination.

## What this prototype does

- Uses Android's Wi-Fi network picker (`WifiNetworkSpecifier`) to connect to the GoPro's Wi-Fi.
- Sends the HERO4 `gpStream` start request to `10.5.5.9`.
- Listens for the camera's UDP stream on port `8554`.
- Sends the received MPEG-TS packets to a local UDP socket.
- Uses FFmpegKit to remux the H.264 video to FLV and publish it to your RTMP server.
- Keeps camera-side sockets on the GoPro Wi-Fi network while leaving FFmpeg's RTMP connection on Android's default internet route.

## Important limitations

This is **source code for a first prototype, not a tested release APK**. I cannot verify it against your physical HERO4 Silver or Samsung phone here. The HERO4 stream protocol is community-documented and can vary with camera firmware and Wi-Fi setup.

The first version does not yet include an in-app video preview, automatic discovery of GoPro SSIDs, support for every GoPro generation, or a polished recovery/reconnect flow. The HERO4 Silver is the initial target. Newer GoPros use different APIs/protocols and should be added separately.

The phone must have internet access while connected to the camera. On some Android/Samsung versions, a Wi-Fi network without internet may cause routing behaviour that needs device-specific testing. If the RTMP connection cannot reach the internet, try enabling mobile data and the phone's setting that keeps mobile data active.

## Build an APK in Android Studio

1. Install Android Studio on a computer.
2. Extract the `GoStream-AndroidStudio.zip` archive.
3. In Android Studio choose **Open**, then select the extracted `GoStream` folder.
4. Allow Gradle sync to finish. Internet is required to download Android Gradle Plugin and the FFmpegKit dependency.
5. Choose **Build → Build Bundle(s) / APK(s) → Build APK(s)**.
6. Android Studio will show the APK location. Usually it is `app/build/outputs/apk/debug/app-debug.apk`.
7. Copy that APK to your Samsung phone and install it. You may need to allow installation from the file manager you use.

## Using it with HERO4 Silver

1. Turn on the HERO4 Silver and enable its wireless/app connection.
2. Find the camera's Wi-Fi network name and password in the camera's wireless settings.
3. Open GoStream, enter that exact SSID and password, then tap **CONNECT TO GOPRO WI-FI**. Android will show a system connection prompt.
4. Enter your RTMP server URL and stream key.
5. Tap **START RTMP STREAM**. Keep the app open during this prototype test.
6. Tap **STOP STREAM** to end it.

## RTMP fields

- Server URL: for example `rtmp://your-server/live`
- Stream key: the key supplied by your streaming provider

The app joins these as `server-url/stream-key`. Do not share your stream key with anyone.

## Technical notes

The HERO4 stream-start request and UDP endpoint are based on the community HERO4 livestreaming notes:
https://github.com/KonradIT/goprowifihack/blob/master/HERO4/Livestreaming.md

FFmpeg dependency:
`dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9`

This dependency is a community-maintained FFmpegKit-compatible Android package. Review its licence and dependency details before distributing the app.


## Build in the cloud (no Android Studio on your phone)

This project includes a GitHub Actions workflow at `.github/workflows/build-apk.yml`.

1. Create a GitHub repository and upload the contents of this project folder to it.
2. Open the repository's **Actions** tab.
3. Select **Build GoStream APK** and tap **Run workflow** (or push to `main`).
4. Wait for the workflow to finish successfully.
5. Open the completed workflow run, scroll to **Artifacts**, and download `GoStream-debug-apk`.
6. Extract the downloaded artifact ZIP; it contains `app-debug.apk`. Transfer/install it on your Samsung.

The workflow builds a debug APK. It is not a signed Play Store release. The build still needs real-device testing with your GoPro HERO4 Silver.
