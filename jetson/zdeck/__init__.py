"""zdeck — the vehicle's physical control surface (Elgato Stream Deck Mini).

Six keys in the cab that drive the moving head without anyone looking at a
screen or typing over ssh. Runs as its own systemd service, deliberately
separate from ``zvision``: a wedged USB button hub must never be able to stall
the threat broadcaster the driver's HUD depends on. That is the same rule that
makes ``zvision.dmx.OlaDmxSink`` swallow its send failures.

Structure mirrors ``zvision``: a pure model with no hardware imports
(:mod:`zdeck.model`), a transport behind a Protocol (:mod:`zdeck.surface`, with
a fake for tests), and the real device isolated in :mod:`zdeck.hardware` so the
whole select→map→render loop is provable with no deck plugged in — exactly how
``detector.py`` keeps cv2 out of the fake path.

Fixture wiring is NOT redefined here. Channel numbers come from
``zvision.tracker.TrackerConfig``, so the deck and the tracker can never
disagree about which channel is the dimmer — a disagreement that on this
fixture would put brightness on the colour wheel (see MOVING-HEAD.md §7).
"""

__version__ = "0.1.0"
