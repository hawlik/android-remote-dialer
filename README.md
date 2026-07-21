# Remote Dialer

Control phone calls from a handlebar mounted Android tablet while your phone stays in your pocket.

Built for motorcycles. The tablet shows an incoming call over your navigation app and lets you accept it, decline it, or decline it with an SMS reply. You can also place calls to your starred contacts and recent callers. Call audio never touches the tablet. It flows between the phone and your Bluetooth helmet intercom, so the tablet is purely a remote control and display.

The two devices talk over Bluetooth Classic (RFCOMM). No internet, no accounts, no root, and the phone keeps its stock dialer.

**Download the APKs for both apps from the [Releases page](https://github.com/hawlik/android-remote-dialer/releases).**

## Apps

* `phone` runs on the phone as a background service. It detects calls, executes commands from the tablet, and sends the SMS replies. It has a small status screen and no dialer UI.
* `tablet` runs on the tablet. It shows the call screen, the quick call list, and settings.

## Requirements

* Two Android devices with **Android 14 (API 34) or newer**
* Bluetooth Classic on both devices
* A phone with a SIM card

## Get the apps

Download `remote-dialer-phone.apk` and `remote-dialer-tablet.apk` from the [Releases page](https://github.com/hawlik/android-remote-dialer/releases).

Or build them yourself with JDK 17: run `./gradlew assembleDebug` and take the APKs from `phone/build/outputs/apk/debug/` and `tablet/build/outputs/apk/debug/`.

## Install step by step

1. Install `remote-dialer-phone.apk` on the phone (the one with the SIM) and `remote-dialer-tablet.apk` on the tablet. The first time you open an APK, Android asks you to allow installs from your browser or file manager. Allow it.

2. Pair the two devices once in Android Bluetooth settings. Open Bluetooth on one device, find the other, and pair. They must show as paired before the apps can connect.

3. Set up the phone app:
   1. Tap **Grant permissions** and allow every permission it asks for.
   2. Tap **Ignore battery optimization** and allow it.
   3. Tap **Start service**. The status dot turns amber, which means the service is running and waiting for the tablet.

4. Set up the tablet app. It opens on the Quick call screen. Tap the gear in the top right to open Settings, then:
   1. Tap **Grant permissions** and allow them.
   2. Tap **Allow full screen calls** and turn it on.
   3. Tap **Show calls over the nav app** and turn it on.
   4. Tap **Ignore battery optimization** and allow it.
   5. Tap **Choose phone** and pick your phone from the paired devices.
   6. Tap **Start service**, then the back arrow to return to Quick call.

5. Within a second or two both status dots turn green. You are connected.

6. Test it. Call the phone from a third number. The tablet should interrupt the screen with the incoming call card. Try accept, decline, and decline with a message.

After this first setup both apps start on boot and reconnect on their own, so you should not need to open either app again.

## Usage

* Incoming call: the tablet interrupts whatever app is on screen with a full screen card. Accept, decline, or pick a quick reply that declines the call and texts the caller from the phone. When the call ends you are back in the app you were using.
* Calling out: open the tablet app. It shows your starred contacts and recent calls. Tap the green phone button on a row to dial. Only the button dials, the row itself does nothing, so scrolling with gloves cannot start a call.
* Quick replies: edit the SMS messages in tablet settings under Quick replies.

## Permissions

This app uses permissions that Google Play restricts (call log, SMS).

### Phone app

| Permission | Why |
|---|---|
| READ_PHONE_STATE | Detect that a call is ringing or ended |
| READ_CALL_LOG | Read the caller number of the ringing call |
| READ_CONTACTS | Resolve caller names and list starred contacts |
| ANSWER_PHONE_CALLS | Answer, reject, and end calls |
| CALL_PHONE | Place outbound calls picked on the tablet |
| SEND_SMS | Send the quick reply when declining with a message |
| BLUETOOTH_CONNECT | Bluetooth link to the tablet |
| POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE | Keep the background service alive with a visible notification |
| RECEIVE_BOOT_COMPLETED | Restart the service after a reboot |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Stop the system from killing the service during long idle |

### Tablet app

| Permission | Why |
|---|---|
| BLUETOOTH_CONNECT | Bluetooth link to the phone |
| USE_FULL_SCREEN_INTENT | Show the incoming call screen when the tablet is locked |
| SYSTEM_ALERT_WINDOW | Show the incoming call screen over the navigation app while it is in use |
| POST_NOTIFICATIONS, FOREGROUND_SERVICE, FOREGROUND_SERVICE_CONNECTED_DEVICE | Keep the link service alive with a visible notification |
| RECEIVE_BOOT_COMPLETED | Restart the service after a reboot |
| REQUEST_IGNORE_BATTERY_OPTIMIZATIONS | Stop the system from killing the service during long idle |

## License

Personal project, use at your own risk. Keep your eyes on the road.
