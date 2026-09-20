# -*- coding: utf-8 -*-
import io

p = r'E:\gitee\NetSight1.0\netsight-ui\src\assets\styles\index.scss'
s = io.open(p, encoding='utf-8').read()

old = "@import './btn.scss';"
new = "@import './btn.scss';\n@import './theme.scss';"

assert old in s
s = s.replace(old, new, 1)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('index.scss patched')
