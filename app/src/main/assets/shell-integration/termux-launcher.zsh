# Termux Launcher OSC 133 shell integration for zsh.
#
# Enable it by adding this line to ~/.zshrc:
#   source ~/.termux/shell-integration/termux-launcher.zsh
#
# This file is managed by Termux Launcher and may be replaced on app updates.

[[ -o interactive ]] || return 0
[[ ${TERMUX_LAUNCHER_ZSH_INTEGRATION_LOADED-} == 1 ]] && return 0
typeset -g TERMUX_LAUNCHER_ZSH_INTEGRATION_LOADED=1
typeset -gr TERMUX_LAUNCHER_ZSH_PROMPT_END=$'%{\e]133;B\a%}'

# Copy recent terminal output from the launcher's own scrollback. The terminal defaults to 50 lines.
cpo() {
    emulate -L zsh -o no_aliases
    local lines=${1:-50}
    print -n -- $'\e]777;cpo;'${lines}$'\a'
}

__termux_launcher_zsh_precmd_start() {
    local -i command_status=$?
    emulate -L zsh -o no_aliases

    # Close the preceding command and mark prompt start before any prompt-framework
    # status blocks are printed, so cpo excludes them with the prompt.
    print -n -- $'\e]133;D;'${command_status}$'\a\e]133;A\a'
    return $command_status
}

__termux_launcher_zsh_precmd_end() {
    local -i command_status=$?
    emulate -L zsh -o no_aliases

    # Run after prompt-framework hooks so B survives frameworks that rebuild PROMPT.
    if [[ ${PROMPT-} != *$'\e]133;B'* ]]; then
        PROMPT="${PROMPT-}${TERMUX_LAUNCHER_ZSH_PROMPT_END}"
    fi
    return $command_status
}

__termux_launcher_zsh_preexec() {
    emulate -L zsh -o no_aliases
    print -n -- $'\e]133;C\a'
}

typeset -ga precmd_functions preexec_functions

# Bracket all existing prompt hooks: A is emitted before prompt/status output and B is
# appended after frameworks have finalized PROMPT. Remove stale entries when re-sourced.
precmd_functions=(__termux_launcher_zsh_precmd_start \
    ${precmd_functions:#__termux_launcher_zsh_precmd_start} )
precmd_functions=(${precmd_functions:#__termux_launcher_zsh_precmd_end} \
    __termux_launcher_zsh_precmd_end)
precmd_functions=(${precmd_functions:#__termux_launcher_zsh_precmd})
preexec_functions=(${preexec_functions:#__termux_launcher_zsh_preexec} __termux_launcher_zsh_preexec)

# Mark the initial prompt when this file is sourced from an already running shell.
__termux_launcher_zsh_precmd_start
__termux_launcher_zsh_precmd_end
