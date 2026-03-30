# bonfirelandsbridge

`bonfirelandsbridge` is the runtime bridge layer for Lands rental renewal fixes, trust/untrust fixes, and in-game rental query commands.

## Current state

- Remaining-time renewal bridge enabled
- Tenant self-heal before renewal/trust/untrust enabled
- Trust bridge enabled
- Untrust bridge enabled
- Player rental query commands enabled
- Admin rental query commands enabled

## Current commands

Player:

- `/blb myrent`
- `/blb myrent detail`

Admin:

- `/blb rentinfo <player>`
- `/blb rentlist [page]`
- `/blb status`
- `/blb reload`
- `/blb calc <baseMaxMinutes> <rentMinutes> <passedSeconds>`
- `/blb runonce`
- `/blb restore [all|<land name>]`

## Performance strategy

- Renewal interception remains event-driven
- Rental queries collect Lands area data on the main thread only
- Remaining-time snapshot reads run asynchronously
- Global rental listing is paged to avoid chat spam and heavy single-response output

## Next milestones

1. Add optional export/report command for rental query results
2. Add richer admin diagnostics for tenant identity anomalies
3. Add safer bootstrap validation against the live Lands schema
4. Add targeted integration tests around query and bridge edge cases
