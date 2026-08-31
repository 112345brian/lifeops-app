"""Per-domain GATHER -> DECIDE -> APPLY logic, one module per life area.

runner.py wires these into the DOMAINS registry and the tick/signal/daily
tiers; it owns no domain logic of its own beyond that dispatch.
"""
