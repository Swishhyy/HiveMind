#!/usr/bin/env python3
"""Read-only fleet monitor. Saves selected metrics, never network keys or launcher data."""
import argparse
import collections
import json
import os
from pathlib import Path
import re
import time
from urllib.parse import urlsplit, urlunsplit
from urllib.request import Request, urlopen


def sample(control, now):
    operations = control.get('operations', {})
    bots = []
    for bot in control.get('bots', []):
        details = operations.get(bot['id'], {}).get('details', {})
        runtime = dict(re.findall(r'(tickMs|confirmMs|corrections|lastProgress|ticks|recoveries|transfers)=(\d+)', details.get('runtime', '')))
        bots.append({'id': bot['id'], 'name': bot.get('name', ''),
                     'online': bool(bot.get('online')),
                     'ageMs': max(0, int(now * 1000) - bot.get('t', 0)),
                     'health': bot.get('health'), 'food': bot.get('food'),
                     'held': details.get('held') == 'true',
                     'runtime': {key: int(value) for key, value in runtime.items()}})
    outcomes = [{'id': action['id'], 'bot': result['bot'], 'stage': result.get('stage', 'applied')}
                for action in control.get('actions', []) for result in action.get('results', [])]
    return {'time': now, 'bots': bots, 'outcomes': outcomes}


def run(url, token, output, duration, interval):
    output.mkdir(parents=True, exist_ok=True)
    start = time.monotonic()
    counts = collections.Counter()
    terminal = {}
    previous = {}
    try:
        with (output / 'soak-metrics.jsonl').open('w', encoding='utf-8') as stream:
            while True:
                now = time.time()
                try:
                    request = Request(url, headers={'Authorization': 'Bearer ' + token})
                    with urlopen(request, timeout=10) as response:
                        body = response.read(8 * 1024 * 1024 + 1)
                    if len(body) > 8 * 1024 * 1024:
                        raise ValueError('Oversized response')
                    record = sample(json.loads(body), now)
                    counts['samples'] += 1
                    for bot in record['bots']:
                        counts['botSamples'] += 1
                        counts['offlineBotSamples'] += not bot['online'] or bot['ageMs'] > 20000
                        runtime = bot['runtime']
                        counts['stalledBotSamples'] += bot['online'] and not bot['held'] and runtime.get('lastProgress', 0) > 120000
                        old = previous.get(bot['id'], {})
                        for key in ('recoveries', 'transfers'):
                            counts[key] += max(0, runtime.get(key, 0) - old.get(key, runtime.get(key, 0)))
                        previous[bot['id']] = runtime
                    for result in record['outcomes']:
                        if result['stage'] in ('completed', 'cancelled', 'failed'):
                            terminal[(result['id'], result['bot'])] = result['stage']
                except Exception as error:
                    counts['pollErrors'] += 1
                    # Exception messages can contain the input URL; only export the type.
                    record = {'time': now, 'error': type(error).__name__}
                stream.write(json.dumps(record, separators=(',', ':')) + '\n')
                stream.flush()
                elapsed = time.monotonic() - start
                if elapsed >= duration:
                    break
                time.sleep(min(interval, duration - elapsed))
    except KeyboardInterrupt:
        pass
    result = {'kind': 'live-controller-observations', 'elapsedSeconds': round(time.monotonic() - start, 1),
              **counts, 'terminalOrders': dict(collections.Counter(terminal.values())),
              'note': 'Stalls are investigation hints; stationary watching is legitimate. Absent receipts are not inferred successes.'}
    (output / 'soak-summary.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('url', help='Dashboard URL, including its fragment token, or the base URL with HIVEMIND_VIEW_TOKEN set')
    parser.add_argument('--hours', type=float, default=8)
    parser.add_argument('--interval', type=float, default=15)
    parser.add_argument('--output', type=Path, default=Path('soak-results'))
    args = parser.parse_args()
    parts = urlsplit(args.url)
    if parts.scheme not in ('http', 'https') or not parts.netloc or parts.username or parts.password:
        parser.error('Use an HTTP(S) controller URL')
    token = parts.fragment or os.environ.get('HIVEMIND_VIEW_TOKEN', '')
    if not token:
        parser.error('Supply the dashboard fragment token or HIVEMIND_VIEW_TOKEN')
    if not 0 <= args.hours <= 168 or args.interval < 1:
        parser.error('Hours must be 0–168 and interval at least one second')
    url = urlunsplit((parts.scheme, parts.netloc, '/api/control', '', ''))
    print(json.dumps(run(url, token, args.output, args.hours * 3600, args.interval), indent=2))


if __name__ == '__main__':
    main()
