# Car Dash

A dashboard for an Android car display, fed by your Android phone over Bluetooth.

It is made of two apps:

- **Car Dash** (`display/`) runs on the car display.
- **Car Dash Companion** (`phone/`) runs on your Android phone and sends the display what it needs.

**Status:** first version. The code compiles and the phone-to-display link is tested, but it has not yet been run on a real car display. Expect a round of fixes after the first drive.

## What it does

- Opens by itself when the car starts.
- Shows what is playing on the phone (Spotify, YouTube Music and so on) with album art, and lets you play, pause and skip from the display.
- Shows WhatsApp and other notifications. You can have one read aloud, dismiss it, or answer with a quick reply or by voice.
- Shows the next turn while Google Maps is navigating on the phone.
- Pops up new messages at the top of the screen while another app, such as Maps, is open.
- Has a Maps button and an Apps screen, so it can also be the display's home screen.

## What it is not

- It is not Android Auto or Apple CarPlay, and it does not mirror the phone's screen.
- The map itself runs on the display (the Maps button opens the display's own Google Maps). Only the next-turn text comes from the phone.
- It does not work with an iPhone. iPhones do not let apps read notifications.
- Sound and phone calls keep working the way they do today, through the display's own Bluetooth.

## 1. Put it on GitHub and get the APKs

1. On github.com, create a new repository. Any name works; it can be private.
2. Upload everything in this folder, including the `.github` folder. On the new repository's page choose **uploading an existing file** and drag all the files and folders in.
   - On a Mac the `.github` folder is hidden. Press `Cmd + Shift + .` in Finder to show it.
3. Open the **Actions** tab and wait for **Build APKs** to get a green tick. It takes about five minutes.
4. Go back to the repository's front page and open **Releases**. Download `CarDash-Display.apk` and `CarDash-Phone.apk`.

If the Actions tab stays empty, the `.github` folder was not uploaded. Upload it again, keeping the path `.github/workflows/build.yml`.

If the build shows a red cross, open it, copy the error text and send it to whoever maintains the code.

## 2. Set up the phone

1. Install `CarDash-Phone.apk` and open **Car Dash Companion**.
2. Tap **Open settings** under *Notification access* and switch it on for Car Dash Companion.
   - On Android 13 and newer the switch may be greyed out. Open the phone's Settings > Apps > Car Dash Companion, tap the three dots at the top right and choose **Allow restricted settings**, then try again.
3. Tap **Allow** under *Nearby devices permission*.
4. Make sure the phone is paired with the car display in Bluetooth settings.

## 3. Set up the display

1. Copy `CarDash-Display.apk` to the display (USB stick or download) and install it.
2. Open **Car Dash** and allow the *Nearby devices* and *Notifications* permissions.
3. When asked, allow **Display over other apps**. Without it Android will not let the app open by itself.
4. The dot at the bottom of the side rail turns green when the phone is connected. On the phone, **Send a test message to the display** checks the link.

Optional, in the display app's **Settings**:

- **Choose phone** if more than one phone is paired with the display.
- **Use Car Dash as the home screen** is the most reliable way to always land on it when the car starts.

## If it does not connect

| What you see | What to do |
| --- | --- |
| "No paired phone found" although the phone is paired | Many aftermarket displays keep Bluetooth to themselves. Use the Wi-Fi backup below. |
| "Phone not reachable over Bluetooth" | Open the phone app once and check that all setup steps show green. |
| Connects, but no music or messages | Notification access is off on the phone (step 2 of the phone setup). |
| Does not open by itself | Allow "Display over other apps", or switch on "Use Car Dash as the home screen". Some displays also have their own "start app on boot" or "keep apps running in sleep" setting; add Car Dash there. |
| Music stutters while it searches for the phone | In Settings > Choose phone, pick your phone so it stops trying the others. |

### Wi-Fi backup

1. Turn on the phone's Wi-Fi hotspot and connect the display to it.
2. In the phone app switch on **Wi-Fi backup** and note the 6-digit code.
3. In the display app's Settings switch on **Wi-Fi backup**, enter the code and tap **Save**.

Use the Wi-Fi backup only with your own hotspot. Anyone on the same Wi-Fi network who knows the code could read the messages being sent.

## Updating

Upload changed files to the same repository. A new build starts by itself and replaces the APKs under Releases. Install the new APKs over the old ones; settings are kept.

## For developers

- Plain Java, no outside libraries. Android Gradle Plugin 8.7.3, Gradle 8.9, Java 17, `minSdk 26`, `targetSdk 34`.
- Build locally with `./gradlew assembleDebug`.
- `shared/src` holds the link code used by both apps: newline-separated JSON messages over a Bluetooth RFCOMM socket or a TCP socket, with pings to detect a dead connection.
- The phone side lives in a `NotificationListenerService`, which Android keeps running. It reads notifications, follows media sessions and listens for the display.
- The display side runs a foreground service that connects to the phone, keeps the state in `DashState` and draws it in `DashboardActivity`. All screens are built in code and sized as a percentage of the screen, so they fit any resolution.
- `signing/cardash.jks` is a throwaway key so that every build can be installed over the previous one. Replace it before publishing the apps anywhere.
