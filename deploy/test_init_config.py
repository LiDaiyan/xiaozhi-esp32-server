import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from ruamel.yaml import YAML

import init_config


class InitConfigTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.config = self.root / "data" / ".config.yaml"
        self.template = self.root / "template.yaml"
        self.template.write_text("server:\n  port: 8000\nmanager-api: {}\n")

    def write(self):
        init_config.write_config(
            self.config, self.template, "http://web:8002/xiaozhi", "test-secret"
        )

    def test_first_run_creates_private_config(self):
        self.write()
        data = YAML().load(self.config)
        self.assertEqual(data["server"]["port"], 8000)
        self.assertEqual(data["server"]["http_port"], 8003)
        self.assertEqual(data["manager-api"]["secret"], "test-secret")
        self.assertEqual(self.config.stat().st_mode & 0o777, 0o600)

    def test_existing_settings_and_comments_survive(self):
        self.config.parent.mkdir()
        self.config.write_text(
            "# custom settings\nserver:\n  port: 9000\n"
            "manager-api:\n  secret: old\n  timeout: 12\nprompt: hello\n"
        )
        self.write()
        self.write()
        data = YAML().load(self.config)
        self.assertEqual(data["server"]["port"], 9000)
        self.assertEqual(data["prompt"], "hello")
        self.assertEqual(data["manager-api"]["timeout"], 12)
        self.assertEqual(data["manager-api"]["url"], "http://web:8002/xiaozhi")
        self.assertIn("# custom settings", self.config.read_text())

    def test_failed_replace_keeps_original_and_cleans_temp_file(self):
        self.write()
        original = self.config.read_bytes()
        with patch.object(init_config.os, "replace", side_effect=OSError):
            with self.assertRaises(OSError):
                self.write()
        self.assertEqual(self.config.read_bytes(), original)
        self.assertEqual(list(self.config.parent.iterdir()), [self.config])

    def test_invalid_existing_config_is_not_overwritten(self):
        self.config.parent.mkdir()
        self.config.write_text("manager-api: invalid\n")
        with self.assertRaises(ValueError):
            self.write()
        self.assertEqual(self.config.read_text(), "manager-api: invalid\n")

    def test_waits_for_authenticated_api_before_returning(self):
        with patch.object(init_config, "read_secret", side_effect=[None, "test", "test"]), \
             patch.object(init_config, "api_ready", side_effect=[False, True]) as ready, \
             patch.object(init_config.time, "sleep"):
            self.assertEqual(init_config.wait_for_secret("http://web"), "test")
        self.assertEqual(ready.call_count, 2)

    def test_timeout_does_not_create_config(self):
        with patch.object(init_config.time, "monotonic", side_effect=[0, 1]):
            with self.assertRaises(TimeoutError):
                init_config.wait_for_secret("http://web", timeout=1)
        self.assertFalse(self.config.exists())


if __name__ == "__main__":
    unittest.main()
