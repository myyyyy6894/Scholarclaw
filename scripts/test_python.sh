#!/usr/bin/env bash
# ============================================================================
# sc-python-ai 可复现测试入口。
#
# 用法：
#   scripts/test_python.sh host       # 宿主 venv（优先 sc-python-ai/.venv，否则 .venv-test）
#   scripts/test_python.sh container  # Python 3.11 测试镜像（scholarclaw/sc-python-ai-test）
#   scripts/test_python.sh all        # 两者都跑
#
# 测试依赖来自 sc-python-ai/requirements-dev.lock.txt，与生产运行时依赖分离。
# ============================================================================
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PY_DIR="${ROOT_DIR}/sc-python-ai"
DEV_LOCK="${PY_DIR}/requirements-dev.lock.txt"
TEST_IMAGE="${SC_PYTHON_TEST_IMAGE:-scholarclaw/sc-python-ai-test:1.0.0}"
PIP_INDEX_URL="${SC_PIP_INDEX_URL:-https://pypi.org/simple}"
MODE="${1:-host}"

run_host() {
  local python_bin
  if [[ -x "${PY_DIR}/.venv/bin/python" ]] \
      && "${PY_DIR}/.venv/bin/python" -c "import pytest" >/dev/null 2>&1; then
    python_bin="${PY_DIR}/.venv/bin/python"
  else
    local venv="${PY_DIR}/.venv-test"
    if [[ ! -x "${venv}/bin/python" ]]; then
      echo "[test_python] creating ${venv}"
      python3 -m venv "${venv}"
      "${venv}/bin/python" -m pip install --quiet --index-url "${PIP_INDEX_URL}" \
        -r "${PY_DIR}/requirements.lock.txt" -r "${DEV_LOCK}"
    fi
    python_bin="${venv}/bin/python"
  fi
  echo "[test_python] host interpreter: $("${python_bin}" -V 2>&1)"
  (cd "${PY_DIR}" && "${python_bin}" -m pytest -p no:cacheprovider)
}

run_container() {
  echo "[test_python] building ${TEST_IMAGE}"
  docker build -t "${TEST_IMAGE}" \
    --build-arg "PIP_INDEX_URL=${PIP_INDEX_URL}" \
    -f "${PY_DIR}/Dockerfile.test" "${PY_DIR}"
  echo "[test_python] running ${TEST_IMAGE}"
  docker run --rm "${TEST_IMAGE}"
}

case "${MODE}" in
  host) run_host ;;
  container) run_container ;;
  all) run_host; run_container ;;
  *)
    echo "usage: $0 [host|container|all]" >&2
    exit 2
    ;;
esac
