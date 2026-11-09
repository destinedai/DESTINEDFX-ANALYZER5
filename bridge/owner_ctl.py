#!/usr/bin/env python3
"""Owner control commands for the bridge. Run with the same environment as the server.

  owner_ctl.py issue-code      print a one-time owner bootstrap code (valid 10 minutes)
  owner_ctl.py rotate-tokens   revoke every app token; apps show their re-pair state

Codes and tokens are only ever printed to the owner's own terminal, never logged or stored in plaintext.
"""
import sys
import app


def main(argv):
    if len(argv) != 2 or argv[1] not in ('issue-code', 'rotate-tokens'):
        print(__doc__, file=sys.stderr)
        return 2
    if argv[1] == 'issue-code':
        code, ttl = app.issue_owner_bootstrap_code()
        print(f'{code} (one-time, valid {ttl // 60} minutes)')
    else:
        print(f'Revoked app tokens for {app.rotate_all_app_tokens()} pairing(s). Apps must re-pair.')
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv))
