"""配置读取与白名单边界（`02` §13.2、`05` §4.6）。"""

from __future__ import annotations

from datetime import timedelta

import pytest

from app.core import config

BASE_ENV = {
    "SC_INTERNAL_SERVICE_TOKEN": "tok",
    "SC_TIMEZONE": "Asia/Shanghai",
}


def test_defaults_are_applied_when_env_is_empty() -> None:
    settings = config.load_settings({})

    assert settings.internal_service_token == ""
    assert settings.internal_token_configured is False
    assert settings.timezone == "Asia/Shanghai"
    assert settings.storage_raw_path == "/data/scholarclaw/storage/raw"
    assert settings.allow_local_model_base_url is False
    assert settings.local_model_allowed_origins == ()
    assert settings.model_connect_timeout_seconds == 5.0
    assert settings.model_read_timeout_seconds == 30.0


def test_reads_whitelisted_values() -> None:
    settings = config.load_settings(
        {
            **BASE_ENV,
            "SC_ALLOW_LOCAL_MODEL_BASE_URL": "true",
            "SC_LOCAL_MODEL_ALLOWED_ORIGINS": "http://127.0.0.1:11434, http://192.168.1.9:11434",
            "SC_MODEL_CONNECT_TIMEOUT_SECONDS": "2.5",
        }
    )

    assert settings.internal_token_configured is True
    assert settings.allow_local_model_base_url is True
    assert settings.local_model_allowed_origins == (
        "http://127.0.0.1:11434",
        "http://192.168.1.9:11434",
    )
    assert settings.model_connect_timeout_seconds == 2.5


def test_whitelist_and_forbidden_sets_are_disjoint() -> None:
    assert config.ALLOWED_ENV_VARS.isdisjoint(config.FORBIDDEN_ENV_VARS)


def test_read_rejects_names_outside_whitelist() -> None:
    """白名单守卫本身也要被断言，否则上面的隔离结论只是"目前恰好没写错"。"""
    with pytest.raises(config.SettingsError):
        config._read({"SC_MASTER_KEY": "x"}, "SC_MASTER_KEY", "default")


def test_unrecognised_boolean_is_rejected() -> None:
    with pytest.raises(config.SettingsError):
        config.load_settings({"SC_ALLOW_LOCAL_MODEL_BASE_URL": "maybe"})


def test_non_numeric_timeout_is_rejected() -> None:
    with pytest.raises(config.SettingsError):
        config.load_settings({"SC_MODEL_CONNECT_TIMEOUT_SECONDS": "fast"})


def test_non_positive_timeout_is_rejected() -> None:
    with pytest.raises(config.SettingsError):
        config.load_settings({"SC_MODEL_READ_TIMEOUT_SECONDS": "0"})


def test_invalid_timezone_is_rejected() -> None:
    with pytest.raises(config.SettingsError):
        config.load_settings({"SC_TIMEZONE": "Mars/Olympus"})


def test_timezone_is_pinned_explicitly_not_taken_from_host() -> None:
    """`ENVIRONMENT.md` ENV-01：对外时间戳必须用配置时区，不依赖宿主机默认时区。"""
    config.load_settings({"SC_TIMEZONE": "UTC"})

    assert config.active_timezone().key == "UTC"
    assert config.now_in_active_timezone().utcoffset() == timedelta(0)

    config.load_settings({"SC_TIMEZONE": "Asia/Shanghai"})

    assert config.now_in_active_timezone().utcoffset() == timedelta(hours=8)


def test_timestamp_format_carries_offset() -> None:
    config.load_settings({"SC_TIMEZONE": "Asia/Shanghai"})
    formatted = config.format_timestamp(config.now_in_active_timezone())

    assert formatted.endswith("+08:00")
    assert "T" in formatted


def test_detect_forbidden_env_vars_returns_names_only() -> None:
    detected = config.detect_forbidden_env_vars(
        {"SC_MASTER_KEY": "super-secret-value", "SC_DB_PASSWORD": "another-secret", "SC_TIMEZONE": "UTC"}
    )

    assert detected == ["SC_DB_PASSWORD", "SC_MASTER_KEY"]
    assert all("secret" not in name for name in detected)


def test_settings_never_surface_forbidden_values() -> None:
    """把敏感变量全部塞进环境，配置对象里也不得出现它们的值。"""
    env = {name: "super-secret-value" for name in config.FORBIDDEN_ENV_VARS}
    env.update(BASE_ENV)

    settings = config.load_settings(env)

    for value in vars(settings).values():
        assert "super-secret-value" not in str(value)
