#!/usr/bin/env bash
# jira.sh - Jira Cloud CLI for the corrcalc projects.
#
# Commands:
#   jira.sh create --type Epic|Task|Sub-task --summary "..." [--description "..."]
#                  [--parent COR-N] [--labels a,b] [--priority High] [--project COR]
#   jira.sh view COR-N
#   jira.sh search --jql "project = COR AND status != Done" [--max 50]
#   jira.sh transition COR-N "In Progress" [--comment "..."]
#   jira.sh comment COR-N "comment text"
#
# Tasks link to their epic and sub-tasks to their parent task through the same
# --parent flag (modern Jira Cloud uses the parent field for both).
# Requires: curl, jq. Auth via JIRA_BASE_URL, JIRA_EMAIL, JIRA_API_TOKEN env vars.
set -euo pipefail

: "${JIRA_BASE_URL:?JIRA_BASE_URL is not set}"
: "${JIRA_EMAIL:?JIRA_EMAIL is not set}"
: "${JIRA_API_TOKEN:?JIRA_API_TOKEN is not set}"

DEFAULT_PROJECT="${JIRA_PROJECT:-COR}"

api() { # api METHOD PATH [JSON_BODY]
    local method=$1 path=$2 body=${3:-}
    local args=(-sS -X "$method" -u "$JIRA_EMAIL:$JIRA_API_TOKEN" -H "Accept: application/json")
    if [ -n "$body" ]; then
        args+=(-H "Content-Type: application/json" -d "$body")
    fi
    curl "${args[@]}" "$JIRA_BASE_URL$path"
}

fail_on_error() { # fail_on_error RESPONSE CONTEXT
    local response=$1 context=$2
    if echo "$response" | jq -e '(.errorMessages // []) + ((.errors // {}) | to_entries | map(.value)) | length > 0' >/dev/null 2>&1; then
        echo "ERROR: $context failed:" >&2
        echo "$response" | jq '{errorMessages, errors}' >&2
        exit 1
    fi
}

adf() { # plain text (\n separated) -> Atlassian Document Format JSON
    jq -Rn --arg text "$1" '
        {type: "doc", version: 1,
         content: ($text | split("\n") | map(select(. != "") |
             {type: "paragraph", content: [{type: "text", text: .}]}))}'
}

cmd_create() {
    local project="$DEFAULT_PROJECT" type="Task" summary="" description="" parent="" labels="" priority=""
    while [ $# -gt 0 ]; do
        case $1 in
            --project)     project=$2; shift 2 ;;
            --type)        type=$2; shift 2 ;;
            --summary)     summary=$2; shift 2 ;;
            --description) description=$2; shift 2 ;;
            --parent|--epic) parent=$2; shift 2 ;;
            --labels)      labels=$2; shift 2 ;;
            --priority)    priority=$2; shift 2 ;;
            *) echo "Unknown option for create: $1" >&2; exit 1 ;;
        esac
    done
    [ -n "$summary" ] || { echo "ERROR: --summary is required" >&2; exit 1; }

    local payload
    payload=$(jq -n \
        --arg project "$project" --arg type "$type" --arg summary "$summary" \
        --arg parent "$parent" --arg labels "$labels" --arg priority "$priority" \
        --argjson description "$([ -n "$description" ] && adf "$description" || echo null)" '
        {fields: ({project: {key: $project}, issuetype: {name: $type}, summary: $summary}
            + (if $parent != "" then {parent: {key: $parent}} else {} end)
            + (if $priority != "" then {priority: {name: $priority}} else {} end)
            + (if $labels != "" then {labels: ($labels | split(","))} else {} end)
            + (if $description != null then {description: $description} else {} end))}')

    local response
    response=$(api POST "/rest/api/3/issue" "$payload")
    fail_on_error "$response" "create $type"
    local key
    key=$(echo "$response" | jq -r '.key')
    echo "Created $type: $key"
    echo "URL: $JIRA_BASE_URL/browse/$key"
}

cmd_view() {
    local key=${1:?usage: jira.sh view COR-N}
    local response
    response=$(api GET "/rest/api/3/issue/$key?fields=summary,status,issuetype,parent,labels,assignee")
    fail_on_error "$response" "view $key"
    echo "$response" | jq -r '
        [.key,
         .fields.issuetype.name,
         .fields.status.name,
         (.fields.parent.key // "-"),
         (.fields.assignee.displayName // "unassigned"),
         .fields.summary] | @tsv' \
        | awk -F'\t' '{printf "%s  [%s/%s]  parent:%s  assignee:%s\n%s\n", $1, $2, $3, $4, $5, $6}'
}

cmd_search() {
    local jql="" max=50
    while [ $# -gt 0 ]; do
        case $1 in
            --jql) jql=$2; shift 2 ;;
            --max) max=$2; shift 2 ;;
            *) echo "Unknown option for search: $1" >&2; exit 1 ;;
        esac
    done
    [ -n "$jql" ] || { echo "ERROR: --jql is required" >&2; exit 1; }

    local response
    response=$(curl -sSG -u "$JIRA_EMAIL:$JIRA_API_TOKEN" -H "Accept: application/json" \
        --data-urlencode "jql=$jql" --data-urlencode "maxResults=$max" \
        --data-urlencode "fields=summary,status,issuetype,parent" \
        "$JIRA_BASE_URL/rest/api/3/search/jql")
    fail_on_error "$response" "search"
    echo "$response" | jq -r '.issues[] |
        [.key, .fields.issuetype.name, .fields.status.name, (.fields.parent.key // "-"), .fields.summary] | @tsv' \
        | column -t -s $'\t'
}

cmd_transition() {
    local key=${1:?usage: jira.sh transition COR-N "Status name"} target=${2:?target status required}
    shift 2
    local comment=""
    while [ $# -gt 0 ]; do
        case $1 in
            --comment) comment=$2; shift 2 ;;
            *) echo "Unknown option for transition: $1" >&2; exit 1 ;;
        esac
    done

    local transitions id
    transitions=$(api GET "/rest/api/3/issue/$key/transitions")
    fail_on_error "$transitions" "list transitions for $key"
    id=$(echo "$transitions" | jq -r --arg name "$target" \
        '.transitions[] | select(.name | ascii_downcase == ($name | ascii_downcase)) | .id' | head -1)
    if [ -z "$id" ]; then
        echo "ERROR: no transition named [$target] for $key. Available:" >&2
        echo "$transitions" | jq -r '.transitions[].name' >&2
        exit 1
    fi

    api POST "/rest/api/3/issue/$key/transitions" "$(jq -n --arg id "$id" '{transition: {id: $id}}')" >/dev/null
    echo "Transitioned $key to [$target]"
    if [ -n "$comment" ]; then
        cmd_comment "$key" "$comment"
    fi
}

cmd_comment() {
    local key=${1:?usage: jira.sh comment COR-N "text"} text=${2:?comment text required}
    local response
    response=$(api POST "/rest/api/3/issue/$key/comment" "$(jq -n --argjson body "$(adf "$text")" '{body: $body}')")
    fail_on_error "$response" "comment on $key"
    echo "Commented on $key"
}

command=${1:-help}
shift || true
case $command in
    create)     cmd_create "$@" ;;
    view)       cmd_view "$@" ;;
    search)     cmd_search "$@" ;;
    transition) cmd_transition "$@" ;;
    comment)    cmd_comment "$@" ;;
    help|--help|-h) sed -n '2,15p' "$0" | sed 's/^# \{0,1\}//' ;;
    *) echo "Unknown command: $command (try: jira.sh help)" >&2; exit 1 ;;
esac
