#!/bin/bash

# Configuration
REPO_URL="https://github.com/bekk/db-scheduler-ui.git"
BRANCH="main"
SUBTREE_PREFIX="vendor/db-scheduler-ui"
SOURCE_DIR="$SUBTREE_PREFIX/db-scheduler-ui-frontend"
TARGET_DIR="db-scheduler-ui-ktor/src/main/resources/static/scheduler-ui"
SCRIPT_NAME=$(basename "$0")

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check if we're in a git repository
check_git_repo() {
    if ! git rev-parse --git-dir > /dev/null 2>&1; then
        log_error "Not in a git repository. Please run this script from your project root."
        exit 1
    fi
}

# Check if subtree exists
subtree_exists() {
    [ -d "$SUBTREE_PREFIX" ]
}

# Initial subtree setup
setup_subtree() {
    log_info "Setting up subtree for the first time..."
    if git subtree add --prefix="$SUBTREE_PREFIX" "$REPO_URL" "$BRANCH" --squash; then
        log_success "Subtree added successfully"
        return 0
    else
        log_error "Failed to add subtree"
        return 1
    fi
}

# Update existing subtree
update_subtree() {
    log_info "Updating existing subtree..."
    if git subtree pull --prefix="$SUBTREE_PREFIX" "$REPO_URL" "$BRANCH" --squash; then
        log_success "Subtree updated successfully"
        return 0
    else
        log_error "Failed to update subtree"
        return 1
    fi
}

# Extract frontend directory
extract_frontend() {
    log_info "Extracting frontend directory..."

    if [ ! -d "$SOURCE_DIR" ]; then
        log_error "Source directory $SOURCE_DIR not found"
        return 1
    fi

    # Remove existing target directory
    if [ -d "$TARGET_DIR" ]; then
        log_info "Removing existing target directory..."
        rm -rf "$TARGET_DIR"
    fi

    # Create target directory
    mkdir -p "$TARGET_DIR"

    # Enable dotglob to include hidden files
    shopt -s dotglob

    # Copy frontend files (including dotfiles)
    local copy_success=true
    if [ "$(ls -A "$SOURCE_DIR" 2>/dev/null)" ]; then
        if ! cp -r "$SOURCE_DIR"/* "$TARGET_DIR"/ 2>/dev/null; then
            copy_success=false
        fi
    else
        log_warning "Source directory is empty"
    fi

    # Disable dotglob
    shopt -u dotglob

    if [ "$copy_success" = true ]; then
        log_success "Frontend files (including dotfiles) extracted to $TARGET_DIR"
        return 0
    else
        log_error "Failed to extract frontend files"
        return 1
    fi
}

# Clean up unnecessary files (optional)
cleanup_files() {
    log_info "Cleaning up unnecessary files..."

    # Remove common development files that aren't needed in production
    local cleanup_patterns=(
        "*.md"
        "*.MD"
        ".gitignore"
        ".git*"
        "package-lock.json"
        "yarn.lock"
        "node_modules"
        ".env*"
        "*.log"
    )

    for pattern in "${cleanup_patterns[@]}"; do
        find "$TARGET_DIR" -name "$pattern" -type f -delete 2>/dev/null || true
        find "$TARGET_DIR" -name "$pattern" -type d -exec rm -rf {} + 2>/dev/null || true
    done

    log_success "Cleanup completed"
}

# Stage changes for commit
stage_changes() {
    log_info "Staging changes..."

    if git add "$TARGET_DIR"; then
        log_success "Changes staged"
        return 0
    else
        log_error "Failed to stage changes"
        return 1
    fi
}

# Show summary
show_summary() {
    echo
    log_info "Summary:"
    echo "  Repository: $REPO_URL"
    echo "  Branch: $BRANCH"
    echo "  Source: $SOURCE_DIR"
    echo "  Target: $TARGET_DIR"
    echo

    if [ -d "$TARGET_DIR" ]; then
        local file_count=$(find "$TARGET_DIR" -type f | wc -l)
        local dir_size=$(du -sh "$TARGET_DIR" 2>/dev/null | cut -f1)
        echo "  Files extracted: $file_count"
        echo "  Total size: $dir_size"
    fi

    echo
    log_success "Update completed! You can now commit the changes:"
    log_info "git commit -m \"Update db-scheduler UI frontend\""
}

# Main execution
main() {
    echo "========================================="
    echo "  DB Scheduler UI Frontend Updater"
    echo "========================================="
    echo

    # Pre-flight checks
    check_git_repo

    # Setup or update subtree
    if subtree_exists; then
        if ! update_subtree; then
            exit 1
        fi
    else
        if ! setup_subtree; then
            exit 1
        fi
    fi

    # Extract frontend
    if ! extract_frontend; then
        exit 1
    fi

    # Optional cleanup
    cleanup_files

    # Stage changes
    if ! stage_changes; then
        exit 1
    fi

    # Show summary
    show_summary
}

# Help function
show_help() {
    echo "Usage: $SCRIPT_NAME [OPTIONS]"
    echo
    echo "Updates the db-scheduler-ui frontend components"
    echo
    echo "Options:"
    echo "  -h, --help     Show this help message"
    echo "  --no-cleanup   Skip cleanup of development files"
    echo "  --dry-run      Show what would be done without making changes"
    echo
    echo "Configuration (edit script to modify):"
    echo "  Repository: $REPO_URL"
    echo "  Branch: $BRANCH"
    echo "  Target: $TARGET_DIR"
}

# Parse command line arguments
case "${1:-}" in
    -h|--help)
        show_help
        exit 0
        ;;
    --dry-run)
        log_info "DRY RUN MODE - No changes will be made"
        log_info "Would update: $TARGET_DIR"
        log_info "From: $REPO_URL ($BRANCH)"
        exit 0
        ;;
    *)
        main "$@"
        ;;
esac
