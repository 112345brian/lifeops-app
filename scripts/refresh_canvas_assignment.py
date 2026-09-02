"""Force a re-check of one Canvas assignment's phases on the next sync.

Use this when better content becomes available for an assignment that was
already synced with thin/generic phases -- e.g. a linked .qmd/.py file
couldn't be fetched the first time (no authenticated browser session), or
the Canvas description was still a submission-boilerplate stub when it
first unlocked. Clears the cached phase labels and the "content_aware"
flag for that assignment, so the retroactive-rename check in
lifeops/domains/canvas.py._canvas_sync regenerates its phases from the
assignment's CURRENT description/linked files and upserts (title,
duration, notes) every already-created FlowSavvy task for that assignment
in place, next time `python -m lifeops.runner` (or the scheduled sync)
runs -- this script only clears the cache, it does not talk to FlowSavvy
itself.

Usage:
    python scripts/refresh_canvas_assignment.py <course_id> <assignment_id>
"""
import sys, os

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
from lifeops.domains.canvas import refresh_assignment


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        sys.exit(1)
    course_id, assignment_id = sys.argv[1], sys.argv[2]
    if refresh_assignment(course_id, assignment_id):
        print(f"Cleared cached phases for assignment {assignment_id} "
              f"(course {course_id}) -- next sync will regenerate and "
              f"upsert its tasks.")
    else:
        print(f"No cached phase state found for assignment {assignment_id} "
              f"in course {course_id} -- nothing to refresh (it may not "
              f"have been synced yet, or was never split into phases).")


if __name__ == "__main__":
    main()
