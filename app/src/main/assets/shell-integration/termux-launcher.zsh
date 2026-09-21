# Termux Launcher OSC 133 shell integration for zsh.
#
# Enable it by adding this line to ~/.zshrc:
#   source ~/.termux/shell-integration/termux-launcher.zsh
#
# This file is managed by Termux Launcher and may be replaced on app updates.

[[ -o interactive ]] || return 0
[[ ${TERMUX_LAUNCHER_ZSH_INTEGRATION_LOADED-} == 1 ]] && return 0
typeset -g TERMUX_LAUNCHER_ZSH_INTEGRATION_LOADED=1

# Copy recent terminal output from the launcher's own scrollback. The terminal defaults to 50 lines.
cpo() {
    emulate -L zsh -o no_aliases
    local lines=${1:-50}
    print -n -- $'\e]777;cpo;'${lines}$'\a'
}

__termux_launcher_zsh_precmd() {
    local -i command_status=$?
    emulate -L zsh -o no_aliases

    # D records the end of command output. A starts the prompt region. This hook is
    # deliberately first so prompt-framework status blocks are excluded from cpo.
    print -n -- $'\e]133;D;'${command_status}$'\a\e]133;A\a'
    return $command_status
}

__termux_launcher_zsh_preexec() {
    emulate -L zsh -o no_aliases
    print -n -- $'\e]133;C\a'
}

typeset -ga precmd_functions preexec_functions

# Remove current and older integration hook names, then run our precmd hook before
# prompt frameworks such as Powerlevel10k.
precmd_functions=(${precmd_functions:#__termux_launcher_zsh_precmd})
precmd_functions=(${precmd_functions:#__termux_launcher_zsh_precmd_start})
precmd_functions=(${precmd_functions:#__termux_launcher_zsh_precmd_end})
precmd_functions=(__termux_launcher_zsh_precmd ${precmd_functions[@]})
preexec_functions=(${preexec_functions:#__termux_launcher_zsh_preexec} __termux_launcher_zsh_preexec)

# Mark the initial prompt when this file is sourced from an already running shell.
__termux_launcher_zsh_precmd
