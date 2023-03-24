# anticropalypse

Discord bot which searches for and deletes images vulnerable to the Acropalypse exploit (CVE-2023-21036).
The public bot can be invited [here](https://discord.com/api/oauth2/authorize?client_id=1086809498632593470&permissions=17179943936&scope=bot%20applications.commands).

Active scanning has been omitted as Discord started stripping excess image data in January.
Instead, this bot's purpose is to search for and delete old vulnerable images.
The bot can optionally archive deleted images (with the excess data stripped!) to an S3 bucket.

## Self-hosting

To use, set the required environment variables (see below) and run `./gradlew :bot:run`.

To create a distributable build, run `./gradlew :bot:distTar` or `./gradlew :bot:distZip` and share/extract the
resulting archive from `bot/build/distributions`.

### Environment variables

| Name            | Description                         |         Required         |
|-----------------|-------------------------------------|:------------------------:|
| `BOT_TOKEN`     | Token for the Discord bot to run as |            ✔️            |
| `S3_BUCKET`     | S3 bucket name to archive images to |      For S3 support      |
| `S3_REGION`     | Region for the S3 archival bucket   |      For S3 support      |
| `S3_ACCESS_KEY` | Your S3 access key                  |      For S3 support      |
| `S3_SECRET_KEY` | Your S3 private key                 |      For S3 support      |
| `S3_ENDPOINT`   | Endpoint for S3 archival bucket     | No, defaults to Amazon's |
