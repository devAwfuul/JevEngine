# JevEngine

A modular Paper 1.21.11 plugin powered by [Jev](https://docs.typesafe.ai).

The engine owns three things: the Jev credentials, the request budget every
module shares, and the branding they all speak in. Features live in modules.

| Module | What it does |
| --- | --- |
| `chatfilter` | Holds each message, judges it with Jev, and releases it |
| `triggerbot` | Captures a five-second combat sample and asks Jev about automation |

## Layout

```
config.yml        API key, request budget, which modules are on, branding
chatfilter.yml    everything the chat filter does
triggerbot.yml    sampling limits and the triggerbot question
```

```
core/               the module contract and shared config
api/                the Jev client, shared by every module
text/               deobfuscation and address scanning, usable by any module
ui/                 branding
modules/chatfilter/ the filter
modules/triggerbot/ combat sampling and triggerbot assessment
```

## Setup

1. Drop the jar in `plugins/` and start the server once.
2. Put your key in `plugins/JevEngine/config.yml`, or set `TYPESAFE_API_KEY` in
   the environment and leave `api.key` blank. The environment is the better
   option if the config is in version control.
3. `/jevengine reload`.

Punishments are off out of the box. The filter still blocks and flags, so you
can watch it work before it can act.

## Commands

`/jevengine`, or `/je`. Engine-wide words are handled by the engine; anything
else is a module's name and the rest of the line goes straight to it.

| Command | What it does |
| --- | --- |
| `/je status` | Model, latency, spend, then each module's own numbers |
| `/je modules` | What is registered and what is running |
| `/je reload [module]` | Re-read config.yml and every module, or just one |
| `/je verbose` | Watch the filter's decisions as they happen |
| `/je test <message>` | Judge a line without sending it or punishing anyone |
| `/je module triggerbot check <player>` | Capture five seconds of combat data and ask Jev for a triggerbot assessment |

`verbose` and `test` are words the chat filter asked to answer to, so they work
without naming it. `/je chatfilter verbose` is the same thing.

Permissions: `jevengine.admin`, `jevengine.chatfilter.verbose`,
`jevengine.chatfilter.notify`, `jevengine.chatfilter.bypass`,
`jevengine.triggerbot.check`.

The triggerbot module records rotations, positions, velocity, health, attacks,
nearby opponents and observed ping only while a staff check is running. Jev is
explicitly told to account for ping, and the result is an investigation lead,
not an automatic punishment. Its shorter form is `/je triggerbot check <player>`.

## Adding a module

1. Implement `JevModule` in `modules/<yourid>/`.
2. Register it in `JevEnginePlugin.registerModules()`.
3. Ship `<yourid>.yml` in resources. `ModuleContext.config()` writes it out the
   first time it is needed.
4. Add a line for it under `modules` in config.yml.

A module gets the shared Jev client, the branding, its own config file, and a
subcommand. It does not reach for the plugin, so the things every module needs
stay in one place rather than being rebuilt per feature. A module that is not
listed under `modules` stays off, so updating the jar never turns a feature on
behind your back. Switching one on needs a restart; `/je reload` re-reads
settings for modules that are already running.

---

# The chat filter

Every message is held for a moment, judged, and then released. Swearing goes
through. Slurs, hate speech, real-world threats, sexual content aimed at a
person, campaigns against one player, scams, adverts for other servers and chat
floods do not.

## How a message is judged

1. **The plugin undoes the disguise.** Zero-width characters, stacked
   diacritics, Cyrillic and Greek lookalikes, small caps, fullwidth text, flag
   emoji, leetspeak, `s p a c e d   l e t t e r s` and `s-t-r-e-t-c-h-e-d`
   spelling are all normalised in code. This happens before the model sees
   anything, because character puzzles are the one thing Jev is genuinely bad at.
   See [Leetspeak](#leetspeak) for how the heavy cases are handled.
2. **The plugin measures what a single message cannot show.** The player's last
   few short messages are joined into one string, anything address-shaped is
   pulled out, and their recent pace is counted. Joining, matching and counting
   are exact operations that code does perfectly.
3. **Jev answers thirteen questions at once.** Is there a slur? A real-world
   threat? Do those fragments spell something the separate messages did not? Is
   this an advert or someone sharing a video? A scam or an ordinary trade?
   Flooding or excitement? One request, answered in parallel.
4. **Code decides.** Thresholds, vetoes and duration caps live in
   `chatfilter.yml` and are plain comparisons. Nothing about the outcome is
   hidden inside the model.

## Low latency mode

On by default. The sender sees their own message immediately, and the room waits
for the verdict.

The delay is only really noticeable to the person typing, so this removes it
where it matters while still keeping anything that breaks a rule away from
everyone else. The trade is that a sender briefly sees a message that is not
going anywhere, which is why the notice they get says so plainly rather than
staying quiet about it.

Turn `chat.low-latency` off if you would rather nobody saw a blocked message at
all, including its author.

## Not flagging real players

The requirement that shaped most of this design was that ordinary players never
get caught. Four things work toward it:

- **A veto question.** Jev is asked separately whether the worst thing in the
  message is ordinary swearing. When it says yes, that answer outranks a hazard
  unless the hazard is near certain.
- **A second opinion.** A `category` question is asked independently. If it
  confidently sees no rule broken while a hazard fired, the two disagree, and a
  disagreement is not enough for a punishment.
- **A severity floor.** A punishment needs the severity score above the line and
  Jev reasonably sure of that score.
- **Context.** The last few lines of chat go with the message, which is how
  mutual trash talk is told apart from one player being hounded.

Each of those can only lower an outcome, never raise it.

## Leetspeak

Leet is handled in two passes, because the two kinds of it need opposite
treatment.

**Light leet** such as `n1gg3r`, `h@ck3r` or `f4gg0t` still has most of its real
letters. The conservative pass swaps characters that sit between letters and
leaves everything else alone, which is why `COME ON!!!!`, `1v1 me` and `I need 4
iron` come through untouched. That pass runs on every message and its result is
the main `deobfuscated` reading.

**Heavy leet** such as `5|_|(|<` or `|\|1663|2` has no real letters at all,
which is exactly the condition the conservative pass needs in order to be safe.
So a second pass reads the whole word as letters, including sequences that spell
one letter with several characters: `|-|` for h, `|\|` for n, `\/\/` for w,
`|_|` for u, `|3` for b, `|<` for k, `|2` for r.

That second pass would wreck ordinary punctuation, so it only runs on words that
already look like leet, and what it produces is sent as an extra `leet_expanded`
reading rather than replacing anything. Jev is told it is the most lossy of the
variants and that the original is what was actually sent.

A word qualifies three ways: it contains both letters and a character nobody
types mid-word (`@ $ # | [ ] { } \ =`), which catches `$hit` and `#ello`; it has
no letters left but is built out of at least two punctuation characters, which
catches `5|_|(|<` while leaving `:)` and `<3` alone; or it mixes letters and
digits densely enough to be deliberate. Doubled v for w is the one letter-only
substitution included, since `vvhore` is a real bypass and `savvy` is not a real
cost.

Bare numbers never qualify, so `1.21.11`, `100%` and `coords 100.5 -200.75`
produce no second reading at all.

Tune it under `deobfuscation.aggressive-leet`, or switch it off there.

## Scams

A scam is the case a wordlist is worst at. The message is friendly, correctly
spelled, and every word in it is innocent. What makes it a scam is the shape of
the offer, which is a judgment rather than a match.

Caught: send-first offers that promise more back, doubling and upgrading
offers, someone claiming to be staff to ask for a password or account details,
links to sites that will ask a player to log in or pay for something the server
does not sell, free ranks or crates dangled behind a link or a DM, and offers to
buy or sell accounts.

Not caught, and spelled out in the question: naming a price, agreeing a swap,
asking someone to send first without promising anything extra, begging for
items, a giveaway the server itself is running, and a player warning others
about a scammer. A bad or one-sided trade is not a scam.

`state.include-player-tenure` sends how many days ago the sender first joined.
The same offer reads very differently from a regular and from an account that
arrived ten minutes ago, and days-since-first-join is an exact number code can
read off the player. The question is told to treat it as context, because being
new is not an offence, and no threshold anywhere looks at it. Turn it off if you
would rather it were not sent.

Scams sit above adverts in the hazard order, so a phishing link is judged as a
scam rather than as someone promoting a server.

## Split messages, adverts and spam

These three need something a single message does not contain, so the plugin
gathers it first and Jev judges the result.

**A slur typed one piece at a time.** `ni` `gg` `er` says nothing in any one
message. The filter keeps the last few short messages per player and joins them,
so Jev is asked about `nigger` rather than about `er`. The run stops at the
first message long enough to be a sentence, which keeps ordinary conversation
from being glued into nonsense, and fragments older than the window are dropped.
Harmless chatter still produces candidates. `ok` plus `lol` becomes `oklol`,
which reads as nothing, and that is the whole answer.

**Adverts.** A pattern pulls out anything address-shaped, including the usual
disguises: `play dot example dot net`, `play(.)example(.)net`, bare IPs with
ports, Discord invites. Jev then decides whether it is an advert, a video link, a
mod page or someone asking a question about another server. Version numbers like
`1.21.11` and coordinates never make it out of the scanner.

**Spam.** The plugin counts messages in a window, counts how many were the same,
and works out the ratio of capitals. Jev reads those as facts and answers the
part that needs judgment: whether this is a flood or someone excited about their
build. Spam blocks the message rather than punishing, because the two look alike
for the first few messages and getting it wrong should cost a player one message.

Three things follow from these, and from scams, being different in kind from
toxicity. An advert is against the rules whether or not it is polite, and a scam
works precisely by being pleasant, so the swearing veto and the harm floor do
not apply to either; `apply-casual-guard` and `apply-severity-floor` are how
that is expressed, and the tighter action thresholds stand in for the missing
gates. A verdict that depended on joined fragments is never cached, and
a cached verdict is never served to a player who is repeating themselves, so the
cache cannot be used to walk past spam detection. And a staff alert shows the
assembled text, because otherwise it reads as a player being punished for typing
`er`.

## Turning on punishments

Run with `/je verbose` for a while first. You are looking for messages where the
action was `BLOCK` and you would have punished, and for anything that reached
`FLAG` that should not have.

When the decisions look right, set `punishments.enabled: true` and fill in
`punishments.commands` with whatever your ban plugin uses.

### Repeated violations

`FLAG` decisions are tracked per player and category. By default, three flags
for the same category within five minutes turn the third flag into a punishment
using that category's configured command. The counter resets after a punishment.
Change the shared rule with `violations.default-threshold` and
`violations.window-seconds`, or override a category under
`violations.thresholds`, for example:

```yaml
violations:
  enabled: true
  default-threshold: 3
  window-seconds: 300
  thresholds:
    spam: 3
    advertising: 2
```

This feature follows `punishments.enabled`. With punishments off, flags remain
flags and no commands are run.

Durations are a separate switch. With `punishments.duration.enabled: false`
every punishment uses `default-tier`. With it on, Jev picks a tier from the
ladder and the plugin turns that tier into a length. It never invents a duration,
which is what keeps two similar messages landing on the same punishment. Every
category also has a cap in `max-tier` that no verdict can exceed.

## Tuning

The questions in `chatfilter.yml` are the moderation policy, in plain English.
Edit them. `state.server-context` is where you describe your server, and a
question takes its sense of what is normal from there, so a roleplay server and
a competitive PvP server want different wording.

If something gets through: raise `policy.guard.threshold` or lower the hazard's
`action` value. If something is caught that should not be: lower
`policy.guard.override-at`, or raise the hazard's `action` value. Check the
change against real phrasing with `/je test` before it touches live chat.

The shipped thresholds are tuned to act readily rather than cautiously. A slur
punishes from 0.68, a scam or an advert from 0.75, and the harm floor sits at
1.6. That catches more, and it will cost some false positives; `/je verbose` is
how you find out whether the trade is right for your server. To go back to a
cautious setup, raise each hazard's `action` by roughly 0.12, put
`policy.severity.min-for-punish` back to 2.0, `policy.guard.threshold` to 0.60
and `policy.guard.override-at` to 0.95.

## Performance

- Nothing touches the main thread. Requests run on virtual threads and held
  messages are re-delivered from there.
- Every question goes in one request, so a message costs one round trip. The
  split question is only sent when consecutive short messages actually produced
  something to join, and the duration question only when Jev is allowed to pick
  durations.
- Repeated harmless lines come from a cache instead of the network. Only
  messages Jev is nearly certain are ordinary are cached, because a verdict can
  depend on the lines around it.
- Per-player history is a dozen entries of stripped text, dropped on logout.

Input tokens are billed at $42 per billion, so a busy server costs cents a day.
`/je status` shows the running total for the whole engine.

## Benchmark

Runs a labeled CSV export of real chat messages through the real questions and
policy in `chatfilter.yml`, in parallel, and reports how Jev's decisions compare
to whatever the old filter did with the same message.

### Where to put the CSV

The default, if you pass no `--csv` at all, is
`src/test/java/dev/awfuul/jevengine/bench/datasets/chat-dataset.csv`. That is
where the current dataset lives. It is not otherwise special: point `--csv` at
any file anywhere to use a different one, nothing here requires it in that
folder. If it stays there, add it to `.gitignore`; a multi-million-line export
does not belong in git history.

A few lines of `# comment` documenting the columns' valid values, if the export
has them at the top of the file, are skipped automatically before the header
row is read.

Expected columns, case-insensitive: `original_message` (or `message` /
`content` / `text` as fallbacks), `was_filtered`, `was_blocked`, `outcome`. Also
read if present, for the disagreement report and the top-offenders table: `id`,
`player_name`, `filtered_message`, `review_status`.

### Running it

```
./gradlew benchmark -Pcsv=/path/to/export.csv
```

The file can be gigantic. Rows stream off disk one at a time rather than being
loaded into memory, and requests are batched (20,000 rows in flight at a time by
default) so a huge file cannot exhaust the heap holding pending futures. Two
options exist specifically for cutting a gigantic file down to a run you're
willing to pay for and wait on:

```
-Plimit=50000          # stop after this many non-blank rows, wherever they fall
-Psample-every=20       # take one row in every 20 instead of a solid run from the top
```

`limit` stops reading the file the moment it has enough rows; it does not first
count the whole file, so setting it low on a huge export is genuinely fast, not
just cheap on API calls. `sample-every` is for when the first N rows would all
be from one point in time or one small group of players and you want a slice
spread across the whole file instead; the two combine, so
`-Plimit=20000 -Psample-every=10` samples every 10th row until it has 20,000.

Other options, all optional:

```
-Pconcurrency=20         # requests in flight at once (default 20)
-Ptimeout-ms=4000        # per-request timeout
-Pmax-retries=2          # retries on 429/529 only
-Pbatch-size=20000       # rows read and submitted per batch
-Pmessage-column=content # override message-column auto-detection
-Pconfig=...             # alternate config.yml (for the API key/model)
-Pchatfilter-config=...  # alternate chatfilter.yml (for the questions/policy)
-Pout=benchmark-results  # where report.txt and disagreements.txt are written
```

It can also be run without Gradle, once compiled, as a plain `--flag value` CLI:

```
java -cp <classes>;<paper-api>;<gson> dev.awfuul.jevengine.bench.ChatFilterBenchmark --csv export.csv
```

### What "accuracy" means here

The old filter's own decision is not proof of what was right, so grading Jev
against it directly would be circular: it would only ever measure agreement
with a system whose mistakes are exactly what prompted this rewrite. Two things
in the data carry real evidence instead:

- `outcome=sent` with neither flag set: delivered, nothing recorded against it.
- `outcome=review-approved`: the old filter blocked it, and a human overturned
  that call. That is a direct, human-sourced statement that the block was wrong.

Both mean the same thing: this message should have gone through unmodified. The
accuracy figures in the report are computed only on that subset, and the report
says up front how large a fraction of the dataset that is. Everything else is
still shown, broken down by outcome and by Jev's action, but kept out of any
number that claims to be graded against the truth.

### Jev sees the message only

No server description, no sender name, no chat history, no rate or repeat
information; just `message.raw` and its deobfuscated/leet-expanded readings,
exactly as production builds them. `split_bypass` and `duration_tier` are left
out of the questions entirely: the dataset has no continuous per-player timeline
to reconstruct fragments from, and duration is a punishment detail this
benchmark isn't scoring.

This means the report is stricter than live production, which also gets
`state.server-context` telling it what your server considers normal. A row
correctly caught here as ordinary trash talk in production may show up here as
one more thing Jev flagged, because it never saw the sentence that told it
trash talk is fine on this server. Bear that in mind before retuning thresholds
solely off this report; check `/je test` for anything that looks like it should
have passed under your actual config.

### Reading the output

`report.txt` has the full breakdown, ending with the top 10 players by Jev
violation count: a `player_name` ranked by how many of their rows Jev put at
BLOCK or PUNISH, alongside their total message count so a high number from
someone who only sent a handful of messages doesn't read the same as one from
someone who sent thousands. This is Jev's own read of the text, not the old
filter's, so it can surface names the old system never touched. `FLAG` is not
counted as a violation there, since that message still went out; it is closer
to what the old filter's `FILTERED` bucket means.

`disagreements.txt` holds every row where Jev's action and the old filter's
action point in different directions, not a sample: one readable block per row,
message text included, meant for reading or grepping rather than for feeding
back into anything automatically. Nothing caps how many go in; a run bounded by
`-Plimit` already bounds this, since there cannot be more disagreements than
rows processed.

## One thing to know

A held message is cancelled and re-sent, so it arrives unsigned, as system chat.
That is true of every plugin that rewrites chat. It is the price of not showing
a slur to the server before deciding about it. If you would rather not pay it,
set `chat.hold-messages: false` and moderation becomes after the fact.
