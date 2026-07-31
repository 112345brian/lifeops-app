`scheduleRoutine` no longer double-books a date that is already busy, which
could happen when a caller passed a `busyDates` set that differed from its
`excludeDates` set, or when the same date appeared twice in `candidateDays`.
A routine's slot conditions are also parsed once up front rather than
re-parsed for every candidate day, so a syntax error in a rarely-reached
later slot now fails immediately instead of only on the day that reaches it.
