# Changelog

## 0.0.1 (unreleased)

Initial public version of HueSync for Android TV.

### Features

- Synchronizes the TV's screen colors with a Philips Hue Entertainment area through a local, encrypted DTLS stream.
- Discovers Hue Bridges with mDNS or accepts a local IPv4 address, then pairs through the Bridge's physical button.
- Supports up to 20 Entertainment channels, including gradient light segments.
- Captures with MediaProjection and GPU sampling, with an automatic ImageReader RGBA fallback if capture fails.
- Provides controls for stream rate, brightness, saturation, smoothing, vertical crop, and left/right calibration.
- Includes an RGB test pattern, a TV dashboard, and a foreground notification with a Stop action.
- Verifies Bridge HTTPS certificates and stores pairing credentials encrypted with Android Keystore.
