# AI Provider Setup

## Gemini Cloud Setup

The first real provider slice uses Google AI Studio for cloud review explanations.

Add this to your local `local.properties` file:

```properties
gemini.api.key=YOUR_KEY_HERE
```

Notes:

- this key is read into `BuildConfig.GEMINI_API_KEY`
- it is local-only and should not be committed
- if the key is missing, cloud review explanation safely returns `null` and the app falls back to the existing unavailable/no-op behavior

## Current Scope

At the moment, only the review explanation provider path is prepared for cloud activation.

Other AI services still use no-op implementations until later provider slices are wired.
