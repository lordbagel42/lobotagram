# Network-side patches

The three patches that keep Reels out at the network and parse layers, plus the
diagnostics patch that tells you which anchor drifted after an Instagram update.
All verified against Instagram 435.0.0.37.76 (versionCode 384109472).

| Patch | File | Extension | Default |
|---|---|---|---|
| Block Reels surfaces | `patches/network/NetworkGatePatch.kt` | `Gate.java` | on |
| Bypass signature check | `patches/signature/SignatureBypassPatch.kt` | — | on |
| Hide suggested Reels in feed | `patches/feed/FeedItemFilterPatch.kt` | `FeedFilter.java` | on |
| Lobotagram diagnostics | `patches/DiagnosticsPatch.kt` | — | off |

## Block Reels surfaces (P1)

**Anchor.** `Lcom/instagram/api/tigon/TigonServiceLayer;->startRequest`, the
method every Instagram HTTP request passes through. The class name and the
method name are not obfuscated; the three parameter types are, so only the
arity is asserted.

**Injection point.** The first `iget-object` whose field type is
`Ljava/net/URI;` — in this release `iget-object v1, v13, LX/4qo;.A08:Ljava/net/URI;`
at instruction index 25. The call goes immediately after it:

```
0031: iget-object v1, v13, LX/4qo;.A08:Ljava/net/URI;
0033: invoke-static/range {v1}, Ldev/lobotagram/extension/Gate;.throwIfBlocked:(Ljava/net/URI;)V
```

That instruction sits inside the method's own `IOException` try block
(`catches: 0x0031 - 0x0042 -> 0x008b`), so throwing is handled by Instagram's
`failRequest` path: the blocked surface shows a network error or nothing at all,
and nothing crashes.

**Fallback.** If that `iget-object` is ever gone, the patch reads the URI field
off the first parameter at method entry instead:

```
move-object/from16 v0, p1
iget-object v0, v0, <requestType>-><uriField>:Ljava/net/URI;
invoke-static/range {v0}, Gate;->throwIfBlocked(Ljava/net/URI;)V
```

The field is found by type (the request class declares exactly one
`Ljava/net/URI;` field), never by name. `move-object/from16` first, so it works
however high the parameter registers sit. Note that at method entry the throw is
*not* inside the try block, so this path is a stopgap: it stops the request but
Instagram sees the `IOException` further up the stack. If the fallback ever
becomes the live strategy, re-point the primary one instead.

**Extension entry point.** `Gate.throwIfBlocked(URI)`. The rules are `String[]`
tables at the top of `Gate.java`, applied in this order:

1. **Allow** `/api/v1/direct_v2/` (prefix), Stories (`/feed/reels_tray`,
   `/feed/get_latest_reel_media/`, `/stories/`) and anything that looks like
   creation (`upload`, `configure`, `/media/configure_to_clips/`).
2. **Block** the Reels surfaces: `/clips/home/`, `/clips/discover`,
   `/clips/homecoming`, `/clips/trend`, `/mixed_media/discover/stream/`,
   `/clips/get_blend_medias/`, `/clips/ads_discover_sync_flow/`,
   `/feed/injected_reels_media`, `/clips/music/`, `/clips/audio/`,
   `/clips/effect/`, `/clips/hashtag/`, `/clips/location/`.
3. **Block** any other `/api/v1/clips/` request whose query carries a pagination
   cursor (`max_id=`, `next_media_ids=`, `page_index=`, `paging_token=`). This is
   the rule that lets one DM-shared reel play and starves the next one.
4. **Block**, if the options are on: `/discover/topical_explore` (Explore) and
   `/qp/batch_fetch/` (nudges).

Inside Instagram "reel" means Story and "clips" means Reel, which is why
`/feed/reels_tray/` is on the allow-list.

**Options.** `blockExplore` and `blockNudges`, both Boolean, both default true.

```bash
java -jar tools/revanced-cli-6.0.0-all.jar patch -b -p build/lobotagram.rvp \
    -O blockExplore=false -e "Block Reels surfaces" ... input.apk
```

They are wired into the extension by rewriting the *dex initial value* of the
`public static boolean` fields `Gate.blockExplore` / `Gate.blockNudges` on the
mutable class def. D8 compiles `public static boolean x = true` into the field's
encoded static value rather than a `sput-boolean` in `<clinit>`, which is why
that is the lever; the fields are deliberately not `final`, because a final one
would let a dexer fold the constant into every read. The patch checks for a
`sput-boolean` to those fields in `Gate.<clinit>` and fails loudly if a future
toolchain starts emitting one — the alternative would be a `Gate.configure(ZZ)V`
call injected into the `startRequest` hook behind a `configured` flag.

## Bypass signature check (P4)

**Anchor.** The string `"Invalid SHA256 key hash"` (matched as a prefix; the
sentence continues "- should be 256-bit."). Two classes contain it: the key-hash
type itself and the builder that constructs one. The patch does not guess — it
takes both as candidates and picks the one type `T` for which the APK has
*exactly one* static `(T)Z` and *exactly one* static `(T,T,Z)Z`. In
435.0.0.37.76 that resolves to `LX/6ls;`, `LX/4Fz;->A01(LX/6ls;)Z` and
`LX/4aB;->A01(LX/6ls;LX/6ls;Z)Z`.

**Injection point.** Both bodies are replaced wholesale:

```
0000: const/4 v0, #int 1
0001: return v0
```

A fresh `MutableMethodImplementation` is installed rather than the instructions
being removed, because the scope check has a try/catch that would otherwise be
left pointing at nothing.

**Why it matters.** A re-signed APK produces a key hash that is not in Meta's
allow-list, both checks fail, and `com.facebook.secure.deeplink` silently drops
the navigation — so a reel shared into a DM opens the home feed instead of the
reel. There is no extension code and no option.

## Hide suggested Reels in feed (P5)

**Anchor.** Any method that contains the wire token `clips_netego`, plus at
least one of `stories_netego` / `suggested_users` / `bloks_netego`, and whose
name contains `parseFromJson` (case-insensitively). The name filter is what
keeps the *serialiser* in the same class — it carries the same tokens — out of
the match. Only one method qualifies here:
`LX/5mk;->unsafeParseFromJson(LX/2q3;)Ljava/lang/Object;`. Every qualifying
method is hooked, so a release that splits the parser in two still works.

**Injection point.** The parse loop reads each item's JSON field name and
dispatches on `String.hashCode()` through a sparse switch. The patch finds that
`hashCode()` call, takes the register it is invoked on, walks back to the
`move-result-object` that filled it, and inserts the filter there:

```
0084: invoke-virtual {v13}, LX/2q3;.A1Z:()Ljava/lang/String;
0087: move-result-object v14
0088: invoke-static/range {v14}, Ldev/lobotagram/extension/FeedFilter;.replaceFeedItemType:(Ljava/lang/String;)Ljava/lang/String;
008b: move-result-object v14
008c: invoke-virtual {v13}, LX/2q3;.A1N:()LX/2dq;
008f: if-eqz v14, 0098
0091: invoke-virtual {v14}, Ljava/lang/String;.hashCode:()I
0095: sparse-switch v0, ...
```

That lands before both the null check and the switch.

**Extension entry point.** `FeedFilter.replaceFeedItemType(String)`. It returns
`lobotagram_blocked` for a Reels unit and the token unchanged for everything
else. An unknown token matches no case, so the item falls through to the
parser's own unknown-type branch (`LX/3l9;.A0S` then `A1e()`, which skips the
value) — no throw, no crash, no empty feed.

The parser in this release knows 56 type tokens and `clips_netego` is the only
Reels one, so the exact-match table has one entry. A prefix rule drops anything
starting with `clips_` as well, since "clips" is Instagram's own word for Reels.

**No option.** The patch is on by default and there is nothing to tune.

## Lobotagram diagnostics

Off by default. Resolves every anchor above, prints what it resolved to, and
throws if any of them failed. Modifies nothing.

```bash
java -Xmx6g -jar tools/revanced-cli-6.0.0-all.jar patch -b \
    -p build/lobotagram.rvp -e "Lobotagram diagnostics" \
    -o /tmp/out.apk --purge -t /tmp/patch-tmp instagram-435.0.0.37.76.apk
```

Output on the pinned release:

```
[lobotagram] === P1 network gate ===
[lobotagram] class:                  Lcom/instagram/api/tigon/TigonServiceLayer;
[lobotagram] method:                 startRequest
[lobotagram] parameter types:        LX/4qo;, LX/4rg;, LX/4sg;
[lobotagram] registers:              16 (4 of them parameters)
[lobotagram] first iget-object of Ljava/net/URI; at index: 25
[lobotagram] request type:           LX/4qo;
[lobotagram]   Ljava/net/URI; fields:  A08
[lobotagram] === P4 signature bypass ===
[lobotagram] key-hash type:          LX/6ls;
[lobotagram] (KeyHash)Z:             LX/4Fz;->A01
[lobotagram] (KeyHash,KeyHash,Z)Z:   LX/4aB;->A01
[lobotagram] === P5 feed item filter ===
[lobotagram]   LX/5mk;->unsafeParseFromJson(LX/2q3;)
[lobotagram]     hook: token in v14, move-result-object at index 74, ...
```

## Reading the trace log

Trace mode is a runtime switch; no rebuild, no reinstall.

```bash
adb shell setprop log.tag.Lobotagram DEBUG   # on
adb logcat -s Lobotagram                     # read
adb shell setprop log.tag.Lobotagram INFO    # off
```

Every request through the gate is logged as one of

```
ALLOW /api/v1/direct_v2/inbox/?persistentBadging=true...
BLOCK reels-surface /clips/home/ /api/v1/clips/home/
BLOCK clips-pagination max_id= /api/v1/clips/connected/
BLOCK explore /api/v1/discover/topical_explore/
```

and every feed unit as `KEEP feed-unit media_or_ad` / `DROP feed-unit
clips_netego`. The `BLOCK` line names the rule that fired, so a rule that is too
broad or too narrow is visible immediately. Because *every* path is logged in
trace mode, this is also how you find an endpoint that moved: open the surface
that still works, read the `ALLOW` lines, add the new path to the right table in
`Gate.java`.

## Fixing it when it breaks

1. Run the diagnostics patch. It names the anchor that failed and prints what is
   there instead.
2. **P1 failed to find the class or method.** Meta moved the network layer.
   Find the class that materialises a `java.net.URI` per request and update
   `TIGON_SERVICE_LAYER` / `START_REQUEST`.
3. **P1 found the method but no `iget-object` of `Ljava/net/URI;`.** The
   fallback takes over automatically if the request class still has exactly one
   URI field; the diagnostics output says which strategy would be used.
4. **P4 could not pin the checks.** The `PatchException` lists every candidate
   type with the methods found for each, with full descriptors. Pick the pair by
   hand and narrow the filter.
5. **P5 found no deserialiser.** Instagram renamed the feed-unit wire tokens
   (`grep -a` the dex files for `netego`) or the generated parsers are no longer
   called `*parseFromJson*`. Update `CLIPS_NETEGO`, `FEED_UNIT_COMPANIONS` or
   `PARSE_FROM_JSON`.
6. **Reels still load.** Turn trace mode on, scroll the surface, and read the
   `ALLOW` lines: the endpoint moved. Add it to `BLOCK_REELS_SURFACES`.
7. **Something that should work is broken.** Same thing from the other side:
   find the `BLOCK` line, and either narrow that rule or add the path to the
   allow-list above it. The allow-list is always consulted first.

## Prior art

`Gate` and the `startRequest` injection are adapted from FeurStagram's
`Block.java` / `NetworkBlockPatch.kt`, `FeedFilter` and the parse hook from its
`FeedItemFilterPatch.kt`, and the signature bypass from its
`SignatureCheckBypassPatch.kt` (all GPLv3). The pagination rule that blocks
reels everywhere except DMs comes from InstaEclipse's `IGNetworkInterceptor`
(GPLv3), which rewrites the URI to `https://127.0.0.1/404` where this project
throws instead.
