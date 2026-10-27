# Personal settings of the dev tools: local/dev.env (git-ignored; template tools/dev.env.example).
# Sourced by the shell scripts in tools/; a variable already set in the environment wins over the file.
_devenv_load() {
  local f k v
  f="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)/local/dev.env"
  [ -f "$f" ] || return 0
  while IFS='=' read -r k v || [ -n "$k" ]; do
    case $k in '' | \#*) continue ;; esac
    [ -n "${!k+x}" ] || export "$k=$v"
  done <"$f"
}
_devenv_load
unset -f _devenv_load
