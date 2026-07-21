# Remote Dialer

Control phone calls from a handlebar mounted Android tablet while your phone stays in your pocket.

Built for motorcycles. The tablet shows an incoming call over your navigation app and lets you accept it, decline it, or decline it with an SMS reply. You can also place calls to your starred contacts and recent callers. Call audio never touches the tablet. It flows between the phone and your Bluetooth helmet intercom, so the tablet is purely a remote control and display.

The two devices talk over Bluetooth Classic (RFCOMM). No internet, no accounts, no root, and the phone keeps its stock dialer.

## Apps

* `phone` runs on the phone as a background service. It detects calls, executes commands from the tablet, and sends the SMS replies. It has a small status screen and no dialer UI.
* `tablet` runs on the tablet. It shows the call screen, the quick call list, and settings.

## Requirements

* Two Android devices with **Android 14 (API 34) or newer**
* Bluetooth Classic on both devices
* A phone with a SIM card

## Install and set up

1. Pair the phone and the tablet once in Android Bluetooth settings.
2. Phone app: grant permissions, allow ignoring battery optimization, start the service.
3. Tablet app: open the gear, grant permissions, allow full screen calls, allow showing over other apps, allow ignoring battery optimization, choose your phone from the paired devices, start the service.

That is the whole setup.

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
