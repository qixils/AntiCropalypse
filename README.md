# March 31st, 2023 Update

As of today, Discord's CDN now strips trailing data from PNGs in-flight, meaning that even old uploads are now safe from
the aCropalypse vulnerability. As such, this bot is no longer necessary, but it will remain online to allow users to
download their archived images.

The original README for the bot can be found below.

# [AntiCropalypse](https://anticropalypse.qixils.dev)

Discord bot which searches for and deletes images vulnerable to the aCropalypse exploit
(CVE-2023-21036 & CVE-2023-28303).
You can learn more about the project and add the public bot to your server
[**here**](https://anticropalypse.qixils.dev).

## Self-hosting

This bot is written in Kotlin and requires Java 17 to compile and run.

### Releases

Running the bot is as simple as downloading the
[latest release](https://github.com/qixils/anticropalypse/releases/latest),
setting the required environment variables (see below),
and running the `bin/bot` script.

### Building from source

To create a distributable build like the published releases, run `./gradlew build`
and share/extract the resulting archive from `bot/build/distributions`.

Otherwise, you can run the bot directly by setting the required environment variables (see below)
and running `./gradlew :bot:run`.

### Environment variables

| Name            | Description                         |         Required         |
|-----------------|-------------------------------------|:------------------------:|
| `BOT_TOKEN`     | Token for the Discord bot to run as |            ✔️            |
| `S3_BUCKET`     | S3 bucket name to archive images to |      For S3 support      |
| `S3_REGION`     | Region for the S3 archival bucket   |      For S3 support      |
| `S3_ACCESS_KEY` | Your S3 access key                  |      For S3 support      |
| `S3_SECRET_KEY` | Your S3 private key                 |      For S3 support      |
| `S3_ENDPOINT`   | Endpoint for S3 archival bucket     | No, defaults to Amazon's |
