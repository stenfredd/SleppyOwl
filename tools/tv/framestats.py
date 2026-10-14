#!/usr/bin/env python3
# Разбивка времени кадра по фазам из `adb shell dumpsys gfxinfo <пакет> framestats` (stdin), см. ui_bench в env.sh.
import sys, statistics as st
lines=sys.stdin.read().splitlines()
hdr=None; rows=[]
for l in lines:
    if l.startswith('Flags,'): hdr=l.strip().strip(',').split(','); continue
    if hdr and l and l[0].isdigit():
        v=l.strip().strip(',').split(',')
        if len(v)>=len(hdr): rows.append(dict(zip(hdr,map(int,v[:len(hdr)]))))
    if l.startswith('---PROFILEDATA---') and rows: pass
rows=[r for r in rows if r['Flags']==0]
def ms(a,b): return [(r[b]-r[a])/1e6 for r in rows if r[a]>0 and r[b]>0]
def p(x): 
    x=sorted(x); return f"p50 {x[len(x)//2]:.1f} p90 {x[int(len(x)*.9)]:.1f} max {x[-1]:.1f}" if x else "-"
print("frames",len(rows))
print("total      ", p(ms('IntendedVsync','FrameCompleted')))
print("input+anim ", p(ms('HandleInputStart','PerformTraversalsStart')))
print("traversal  ", p(ms('PerformTraversalsStart','DrawStart')))
print("record draw", p(ms('DrawStart','SyncQueued')))
print("sync       ", p(ms('SyncStart','IssueDrawCommandsStart')))
print("issue cmds ", p(ms('IssueDrawCommandsStart','SwapBuffers')))
print("swap       ", p(ms('SwapBuffers','FrameCompleted')))
if 'GpuCompleted' in hdr: print("gpu        ", p(ms('IssueDrawCommandsStart','GpuCompleted')))
