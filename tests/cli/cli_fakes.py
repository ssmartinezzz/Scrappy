"""Test doubles shared by the TUI and plain-runner suites, which drive the
same services through two presenters."""


class FakeFlareSolverr:
    def __init__(self, line=None) -> None:
        self.stopped_with = []
        self.ensured_with = []
        self.line = line

    def stop_flaresolverr(self, cfg):
        self.stopped_with.append(cfg)

    def ensure_flaresolverr(self, cfg):
        self.ensured_with.append(cfg)
        return self.line


class FakeLanProxy:
    def __init__(self) -> None:
        self.stopped_with = []

    def stop_proxy(self, cfg):
        self.stopped_with.append(cfg)


class QuietFollower:
    """Default notice follower: tests that run `start` must not reach a real
    EventFollower, which would retry against a fake rest in the background."""

    def events(self):
        return iter(())

    def close(self):
        pass
