# Cam's Recently Closed GitHub Issues with no Milestone Slack Bot

A Slack app that will post a message when about recently closed GitHub issues that weren't tagged with a milestone.

![Screenshot](docs/screenshot.png)

### Running:

```sh
# To see a list of possible arguments
clojure -M -m closed-issues-bot.core --help

# To run the command (these tokens are fake, but the shape is correct)
clojure -M -m closed-issues-bot.core \
  --slack-oauth-token xoxb-3104327438-7923114252020-rAaqkKTnbGy4dwLalHridEiu \
  --github-token 17c52c6b16f7187c63813741266ab57c0ce72697 \
  --slack-channel pod-product

# With compiled uberjar (see below)
java -jar closed-issues-bot.jar --help
```

### Building an uberjar:

```sh
clojure -X:uberjar
```

Will generate `closed-issues-bot.jar`.

### CLI Options:

#### Required options

The following options are required:

##### `--slack-oauth-token`

Create a new Slack application by visiting https://api.slack.com/apps > `Create an app` > `From scratch`. Add it to
the desired workspace, then click `Add features and functionality` > `Bots` > `Review Scopes to Add`. Add the
following **Bot Token Scopes**:

- `chat:write`
- `chat:write.customize`
- `chat:write.public`
- `users:read`

Finally, click `Install to Workspace` to get the **Bot User OAuth Token**.

##### `--github-token`

Go to https://github.com/settings/tokens > `Personal access tokens` and create a new token. For public repos, this token doesn't need
any special permissions besides the default permission `public_repo`.

##### `--slack-channel`

The channel to post the message to. Does not need to start with `#`. Can also be a user (prefix with `@`).

#### Optional

The following additional configuration options are supported:

##### `--github-repo`

The GitHub repo to check for recently closed issues in. By default, `metabase/metabase`.

##### `--days`

The maximum number of days ago an issue could have been closed to be considered "recent". Issues closed longer than
this many days ago will not be posted by the bot, regardless of whether they have a milestone or not. By default, `14`.

##### `--slack-bot-name`

The name that should be used for the Slack app when it posts. By default, `GH Closed Issues With No Milestone Bot`.

##### `--slack-bot-emoji`

The emoji that should be used for the Slack app when it posts. By default, `:sad`.

##### `--excluded-github-labels`

GitHub labels that mean an issue should be ignored. Use commas to separate labels. By default these labels are ignored:

- `.Documentation`
- `.Duplicate`
- `.Unable to Reproduce`
- `.Won't Fix`
- `Type:Question`

### REPL Usage

To set options in the REPL, use `closed-issues-bot.config/set-option!`:

```clj
(closed-issues-bot.config/set-option! :excluded-github-labels #{"abc" "def"})
```

### License

Copyright 2021, Metabase, Inc. Licensed under the MIT License.
