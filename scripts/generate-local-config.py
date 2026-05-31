import importlib.util
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CONFIG = ROOT / "config.py"
OUTPUT = ROOT / "backend" / "src" / "main" / "resources" / "application-local.yml"


def load_config():
    if not CONFIG.exists():
        raise SystemExit("请先复制 config.example.py 为 config.py，并填写本地密钥。")
    spec = importlib.util.spec_from_file_location("local_life_config", CONFIG)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def value(module, name, default=""):
    return getattr(module, name, default)


def main():
    cfg = load_config()
    OUTPUT.write_text(
        f"""external:
  amap:
    enabled: true
    base-url: {value(cfg, "AMAP_BASE_URL", "https://restapi.amap.com")}
    web-service-key: {value(cfg, "AMAP_WEB_SERVICE_KEY")}
    city: {value(cfg, "AMAP_CITY", "大连")}
    default-origin: {value(cfg, "AMAP_DEFAULT_ORIGIN", "121.588000,38.883000")}
    timeout-ms: {value(cfg, "AMAP_TIMEOUT_MS", 3000)}
  search:
    enabled: true
    provider: tavily
    base-url: {value(cfg, "TAVILY_BASE_URL", "https://api.tavily.com")}
    tavily-api-key: {value(cfg, "TAVILY_API_KEY")}
    max-results: {value(cfg, "TAVILY_MAX_RESULTS", 5)}
    timeout-ms: {value(cfg, "TAVILY_TIMEOUT_MS", 3000)}
  llm:
    enabled: true
    provider: mimo
    api-key: {value(cfg, "MIMO_API_KEY")}
    base-url: {value(cfg, "MIMO_BASE_URL", "https://token-plan-cn.xiaomimimo.com/v1")}
    model: {value(cfg, "MIMO_MODEL", "mimo-v2.5-pro")}
    max-tokens: {value(cfg, "MIMO_MAX_TOKENS", 1024)}
    temperature: {value(cfg, "MIMO_TEMPERATURE", 0.1)}
    timeout-ms: {value(cfg, "MIMO_TIMEOUT_MS", 3000)}
    router-mode: {value(cfg, "MIMO_ROUTER_MODE", "primary-fallback")}
    secondary:
      enabled: true
      api-key: {value(cfg, "MIMO_SECONDARY_API_KEY")}
      base-url: {value(cfg, "MIMO_SECONDARY_BASE_URL")}
      model: {value(cfg, "MIMO_SECONDARY_MODEL")}
      max-tokens: {value(cfg, "MIMO_SECONDARY_MAX_TOKENS", 1024)}
      timeout-ms: {value(cfg, "MIMO_SECONDARY_TIMEOUT_MS", 4000)}
""",
        encoding="utf-8",
    )
    print(f"已生成 {OUTPUT}")


if __name__ == "__main__":
    main()
