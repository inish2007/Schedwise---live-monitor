#!/usr/bin/env python3
"""Manual, identity-checked recovery. Refuses to compete with a live guardian."""
import argparse, runpy, sys
from pathlib import Path
parser=argparse.ArgumentParser(); parser.add_argument('--data',default='data');args=parser.parse_args()
script=Path(__file__).resolve().parents[1]/'backend/src/main/resources/shield/guardian.py'
sys.argv=[str(script),'--data',str(Path(args.data).resolve()),'--recover']
runpy.run_path(str(script),run_name='__main__')
