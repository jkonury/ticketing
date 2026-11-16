"""jcmd Compiler.codelist 차분(진단 전용): 사용법 codelist-diff.py <외부.txt> <내부A.txt> <내부B.txt>
외부에서 C2(level 4)인데 내부 두 회차 모두 C2가 아닌 메서드를 센다. 내부 A·B끼리의 차이가 노이즈 하한이다."""
import sys, collections
def load(p):
    m = {}
    for line in open(p, errors='replace'):
        t = line.split(None, 3)
        if len(t) < 4 or not t[0].isdigit() or int(t[2]) != 0: continue
        m[t[3].rsplit(' [0x',1)[0]] = max(m.get(t[3].rsplit(' [0x',1)[0],0), int(t[1]))
    return m
ext, a, b = (load(p) for p in sys.argv[1:4])
# 외부 C2이고 두 내부 회차 모두 C2가 아님 / 아예 없음
both = [s for s,l in ext.items() if l==4 and a.get(s,-1)!=4 and b.get(s,-1)!=4]
absent = [s for s in both if s not in a and s not in b]
rev = [s for s,l in a.items() if l==4 and b.get(s,-1)==4 and ext.get(s,-1)!=4]
print(f'외부 C2 & 내부 두 회차 모두 비C2: {len(both)} (두 회차 모두 미컴파일 {len(absent)}); 역방향(내부 A·B C2 & 외부 비C2): {len([s for s,l in a.items() if l==4 and b.get(s,-1)==4 and ext.get(s,-1)!=4])}')
def grp(s,n=4): return '.'.join(s.split('(')[0].split('.')[:-1][:n])
c = collections.Counter(grp(s,6) for s in absent)
print('미컴파일(두 회차 모두) 상위 클래스군:')
for g,n in c.most_common(22): print(f'  {n:4d} {g}')
