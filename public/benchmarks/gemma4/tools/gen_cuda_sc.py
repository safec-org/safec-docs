# Embeds src/gemma4_cuda.cuh into src/gemma4_cuda.sc as a C string literal (from src/gemma4_cuda.sc.in).
import os
here = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'src')
src = open(os.path.join(here, 'gemma4_cuda.cuh')).read().rstrip('\n').split('\n')
lines = ['    "' + l.replace('\\', '\\\\').replace('"', '\\"') + '\\n"' for l in src]
tpl = open(os.path.join(here, 'gemma4_cuda.sc.in')).read()
open(os.path.join(here, 'gemma4_cuda.sc'), 'w').write(tpl.replace('@@SOURCE@@', '\n'.join(lines)))
print(len(lines), 'kernel source lines embedded')
