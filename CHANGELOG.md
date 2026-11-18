# Changelog

## 0.0.3

### Features

- Adds **Prioritize video playback**, enabled by default. It samples a 128-pixel-wide image at 24 points per Hue channel and adapts analysis between 10 and 20 fps. Turning it off restores the 384-pixel capture, 96 points per channel, and the selected analysis rate.
- Adds **Try compatibility capture** during GPU synchronization. It switches the current session to the RGBA sampler using the same virtual display and capture consent.

### Performance

- During continuous video, the lightweight GPU sampler reads the latest frame at each analysis deadline and uses precomputed sample weights. On supported devices it requests low GPU priority and reads colors asynchronously with OpenGL ES 3, with at most one transfer in flight.
- Advances light transitions at the selected Hue stream rate even when capture delivers fewer frames. The stream reuses its color buffers and skips recalculating settled colors.

## 0.0.2 (released)

### Performance

- Speeds up the ImageReader RGBA fallback by reading each of the four bilinear sample pixels once as a packed 32-bit value, then extracting red, green, and blue together. This replaces 12 separate byte reads per sampled point with 4 packed reads while preserving the existing interpolation and color conversion. The sampler still honors row and pixel strides and either buffer byte order.

### Tests

- Covers packed pixel reads with different byte orders, direct and read-only buffers, and padded rows.

## 0.0.1 (released)

Initial public version of HueSync for Android TV.

### Features

- Synchronizes the TV's screen colors with a Philips Hue Entertainment area through a local, encrypted DTLS stream.
- Discovers Hue Bridges with mDNS or accepts a local IPv4 address, then pairs through the Bridge's physical button.
- Supports up to 20 Entertainment channels, including gradient light segments.
- Captures with MediaProjection and GPU sampling, with an automatic ImageReader RGBA fallback if capture fails.
- Provides controls for stream rate, brightness, saturation, smoothing, vertical crop, and left/right calibration.
- Includes an RGB test pattern, a TV dashboard, and a foreground notification with a Stop action.
- Verifies Bridge HTTPS certificates and stores pairing credentials encrypted with Android Keystore.
