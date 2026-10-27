# Neo Noted Driver Android

A minimal, hardware-keyboard-only Android IME for the standard
[Noted layout](https://github.com/dariogoetz/noted-layout). It has no software
keyboard, network, accessibility, root, or storage-permission component.

The driver includes all six standard Noted layers, conditional semantic slots 7
and 8, the official dead keys, and the complete Compose table. Printable output
is committed as Unicode through Android's `InputConnection`; navigation, editing,
function, numpad, and Ctrl/Alt/Meta shortcuts are emitted as paired native key
events.

## Modifiers

- Caps Lock and the physical Ä position are the two Mod3 keys.
- The ISO key between left Shift and Y/Z and right Alt are the two Mod4 keys.
- Press both Mod3 keys without another key between them to start Compose.
- Press both Shift keys to toggle ShiftLock.
- Press both Mod4 keys to toggle Mod4Lock.
- Right Alt is Noted Mod4, never an implicit Ctrl+Alt shortcut modifier.

Linux evdev scan codes are preferred because they identify physical ISO keys
independently of the host layout. The important positions are ISO extra `86`,
`#` `43`, Ä/AE `40`, grave `41`, AE11/minus `12`, AE12/dead keys `13`, and
AD12/Noted ß/ẞ `27`. When a device supplies no scan
code, the fallback follows Android key codes and the historical German-ISO
convention used by this app: `KEYCODE_BACKSLASH` is the ISO extra Mod4 key and
`KEYCODE_POUND` is the `#` position. Devices that collapse both positions to the
same key code require working evdev scan codes for an unambiguous mapping.

## Optional legacy overrides

For backwards compatibility, the driver still attempts to read
`/sdcard/NotedDriver/noted_mappings.json`. Valid legacy `TEXT`, `KEYCODE`, and
`SEQUENCE` entries override the corresponding M1–M6 key; malformed files are
ignored and the bundled canonical layout remains active. No storage permission
is requested. Consequently, direct `/sdcard` access is normally available only
to older installs/devices or files exposed under compatible legacy storage
rules; modern scoped-storage Android versions may make this optional file
unreadable. The bundled offline canonical data is always the fallback.

## Rebuilding generated data

The app never reads another checkout or downloads layout data at runtime. To
rebuild the bundled asset from the pinned developer source:

```sh
python3 tools/generate_noted_data.py \
  /path/to/noted-layout-windows-ahk app/src/main/assets/noted_data.json
```

The generator refuses sources whose complete 91-row table or 7,622-result
Compose dictionary differs from the pinned hashes. Attribution and licensing are
in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

## Build

Use an Android SDK containing platform 34, then run:

```sh
./gradlew --no-daemon testDebugUnitTest assembleDebug assemblePreview lintDebug
```

Enable the IME in Android's physical-keyboard/input-method settings after
installing the APK. There is intentionally no keyboard UI.

## Safe preview installation and signing

The `preview` build is labeled **Noted Preview** and uses the separate application
ID `com.sjdev.noteddriverandroid.preview`. Install `app-preview.apk`, enable
**Noted Preview** under Android input-method settings, and select it for physical
keyboard input. The original driver can remain installed and can be selected
again at any time. Android's standard warning about input methods applies to
all IMEs; this driver itself is offline and does not log typed text.

The normal `debug` and `release` application ID remains
`com.sjdev.noteddriverandroid`. The APK previously committed to this repository
was signed with a different debug key from the current build machine. Without
the original signing key, a newly built canonical APK cannot update that old
installation in place. Do not uninstall the working driver just to bypass this
check; use the side-by-side preview instead. No signing key belongs in Git.

## Focus changes and event ownership

Version 1.1.1 reconciles reliable Android modifier metadata on each input event,
so physically held Ctrl, Shift, Meta, and left Alt remain effective after an
editor restart without replaying old key presses. Side-specific right Alt is
Noted Mod4 rather than ordinary Alt. Metadata is event-local; independent
physical presses are owned per input device, and zero metadata cannot clear
another observed held key. Caps/Num/Scroll lock toggle bits are not physical
Mod3 presses. Compose buffers and internal layer locks still reset on focus
changes. If a physically held Mod3 key spans that change, Android provides no
reliable held-key state in those toggle bits; release and press that Mod3 key
again. No privileged input-state query is used.

The Android adapter consumes a release only for a press it owns. Releases whose
downs were delegated while no input connection existed go back to Android's
normal handling. A small tested input-session boundary balances synthetic keys
against the previous connection on replacement or loss, never the new editor.
Tests exercise this routing with in-memory editor targets; actual Android OS
lifecycle and physical keyboard behavior still need an on-device check.

## Verification boundary

The pure Kotlin tests cover the pinned data, all 32 modifier/lock states,
conditional slots 7/8, dead keys and Compose, native action ownership/repeats,
shortcuts, Unicode, reset behavior, and legacy override validation. Builds and
Android lint are separate checks. They do not establish that every physical
keyboard, Android vendor input stack, editor, or system shortcut behaves the
same way. Test the preview on the intended handset and keyboard before replacing
a working setup. Android may reserve some system shortcuts before an IME sees
them; unsupported shortcut symbols are consumed rather than inserted as text.
