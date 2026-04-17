#!/usr/bin/env python3
"""
Utility script to fetch recent UI dumps from Supabase for debugging.

Usage:
    # Get the 5 most recent dumps
    ./get_recent_ui_dumps.py

    # Get a specific dump by ID
    ./get_recent_ui_dumps.py --id 123

    # Get dumps for a specific reason
    ./get_recent_ui_dumps.py --reason whatsapp_chat_not_found

    # Get dumps from the last hour
    ./get_recent_ui_dumps.py --since 1h

    # Show full UI hierarchy (default is truncated)
    ./get_recent_ui_dumps.py --full

    # Save dump to file
    ./get_recent_ui_dumps.py --id 123 --save
"""

import argparse
import os
import sys
from datetime import datetime, timedelta

# Add parent directory to path to import supabase_client
sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'whizvoice'))

try:
    from supabase import create_client
except ImportError:
    print("Error: supabase package not installed. Run: pip install supabase")
    sys.exit(1)

# Load Supabase credentials
SUPABASE_URL = os.getenv("SUPABASE_URL", "")
SUPABASE_KEY = os.getenv("SUPABASE_SERVICE_ROLE", os.getenv("SUPABASE_KEY", ""))

if not SUPABASE_URL or not SUPABASE_KEY:
    # Try to load from constants.py
    try:
        sys.path.insert(0, os.path.join(os.path.dirname(__file__), '..', 'whizvoice'))
        from constants import SUPABASE_URL, SUPABASE_SERVICE_ROLE as SUPABASE_KEY
    except ImportError:
        print("Error: SUPABASE_URL and SUPABASE_SERVICE_ROLE environment variables not set")
        print("Set them or ensure whizvoice/constants.py exists")
        sys.exit(1)

supabase = create_client(SUPABASE_URL, SUPABASE_KEY)


def parse_since(since_str: str) -> datetime:
    """Parse a duration string like '1h', '30m', '2d' into a datetime."""
    now = datetime.utcnow()

    if since_str.endswith('h'):
        hours = int(since_str[:-1])
        return now - timedelta(hours=hours)
    elif since_str.endswith('m'):
        minutes = int(since_str[:-1])
        return now - timedelta(minutes=minutes)
    elif since_str.endswith('d'):
        days = int(since_str[:-1])
        return now - timedelta(days=days)
    else:
        raise ValueError(f"Invalid duration format: {since_str}. Use '1h', '30m', '2d', etc.")


def truncate_hierarchy(hierarchy: str, max_lines: int = 50) -> str:
    """Truncate UI hierarchy to first N lines."""
    lines = hierarchy.split('\n')
    if len(lines) <= max_lines:
        return hierarchy
    return '\n'.join(lines[:max_lines]) + f"\n\n... ({len(lines) - max_lines} more lines, use --full to see all)"


def format_dump(dump: dict, show_full: bool = False) -> str:
    """Format a dump record for display."""
    lines = [
        f"{'='*60}",
        f"ID: {dump['id']}",
        f"Created: {dump['created_at']}",
        f"Reason: {dump['dump_reason']}",
        f"Error: {dump.get('error_message') or 'N/A'}",
        f"Package: {dump.get('package_name') or 'N/A'}",
        f"Device: {dump.get('device_manufacturer') or '?'} {dump.get('device_model') or '?'}",
        f"Android: {dump.get('android_version') or '?'}",
        f"Screen: {dump.get('screen_width') or '?'}x{dump.get('screen_height') or '?'}",
        f"App Version: {dump.get('app_version') or 'N/A'}",
        f"User ID: {dump.get('user_id') or 'N/A'}",
    ]

    recent_actions = dump.get('recent_actions')
    if recent_actions:
        lines.append(f"Recent Actions: {recent_actions}")

    lines.append(f"\n--- UI Hierarchy ---")

    hierarchy = dump.get('ui_hierarchy', '')
    if show_full:
        lines.append(hierarchy)
    else:
        lines.append(truncate_hierarchy(hierarchy))

    return '\n'.join(lines)


def get_dump_by_id(dump_id: int) -> dict:
    """Fetch a specific dump by ID."""
    result = supabase.table("screen_agent_ui_dumps")\
        .select("*")\
        .eq("id", dump_id)\
        .execute()

    if not result.data:
        return None
    return result.data[0]


def get_recent_dumps(limit: int = 5, reason: str = None, since: datetime = None) -> list:
    """Fetch recent dumps with optional filters."""
    query = supabase.table("screen_agent_ui_dumps")\
        .select("*")\
        .order("created_at", desc=True)\
        .limit(limit)

    if reason:
        query = query.eq("dump_reason", reason)

    if since:
        query = query.gte("created_at", since.isoformat())

    result = query.execute()
    return result.data


def list_dump_reasons() -> list:
    """Get a summary of dumps grouped by reason."""
    result = supabase.table("screen_agent_ui_dumps")\
        .select("dump_reason")\
        .execute()

    # Count by reason
    reasons = {}
    for row in result.data:
        reason = row['dump_reason']
        reasons[reason] = reasons.get(reason, 0) + 1

    return sorted(reasons.items(), key=lambda x: -x[1])


def main():
    parser = argparse.ArgumentParser(description="Fetch UI dumps from Supabase for debugging")
    parser.add_argument("--id", type=int, help="Get a specific dump by ID")
    parser.add_argument("--reason", type=str, help="Filter by dump reason")
    parser.add_argument("--since", type=str, help="Get dumps since duration (e.g., 1h, 30m, 2d)")
    parser.add_argument("--limit", type=int, default=5, help="Number of dumps to fetch (default: 5)")
    parser.add_argument("--full", action="store_true", help="Show full UI hierarchy (default is truncated)")
    parser.add_argument("--save", action="store_true", help="Save dump to file")
    parser.add_argument("--list-reasons", action="store_true", help="List all dump reasons with counts")

    args = parser.parse_args()

    if args.list_reasons:
        print("Dump reasons (by count):")
        print("-" * 40)
        reasons = list_dump_reasons()
        if not reasons:
            print("No dumps found")
        else:
            for reason, count in reasons:
                print(f"  {reason}: {count}")
        return

    if args.id:
        dump = get_dump_by_id(args.id)
        if not dump:
            print(f"No dump found with ID {args.id}")
            return

        print(format_dump(dump, show_full=args.full))

        if args.save:
            filename = f"ui_dump_{dump['id']}_{dump['dump_reason']}.txt"
            with open(filename, 'w') as f:
                f.write(dump.get('ui_hierarchy', ''))
            print(f"\nSaved to: {filename}")
        return

    since = None
    if args.since:
        try:
            since = parse_since(args.since)
        except ValueError as e:
            print(f"Error: {e}")
            return

    dumps = get_recent_dumps(limit=args.limit, reason=args.reason, since=since)

    if not dumps:
        print("No dumps found")
        return

    print(f"Found {len(dumps)} dump(s):\n")

    for dump in dumps:
        print(format_dump(dump, show_full=args.full))
        print()


if __name__ == "__main__":
    main()
