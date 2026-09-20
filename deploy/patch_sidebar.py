# -*- coding: utf-8 -*-
import io

p = r'E:\gitee\NetSight1.0\netsight-ui\src\assets\styles\sidebar.scss'
s = io.open(p, encoding='utf-8').read()

old = '''    .el-menu-item, .el-submenu__title {
      overflow: hidden !important;
      text-overflow: ellipsis !important;
      white-space: nowrap !important;
    }

    // menu hover'''

new = '''    .el-menu-item, .el-submenu__title {
      overflow: hidden !important;
      text-overflow: ellipsis !important;
      white-space: nowrap !important;
    }

    // 选中项左侧 3px 蓝紫渐变高亮条（方案 8.4）
    .el-menu-item.is-active {
      position: relative;
      background: linear-gradient(90deg, rgba(99,102,241,.18), rgba(99,102,241,.02)) !important;
      color: $base-menu-color-active !important;

      &::before {
        content: "";
        position: absolute;
        left: 0;
        top: 0;
        bottom: 0;
        width: 3px;
        background: linear-gradient(180deg, #6366F1, #8B5CF6);
        border-radius: 0 3px 3px 0;
      }
    }

    // 子菜单展开项高亮
    .el-submenu.is-active > .el-submenu__title {
      color: $base-menu-color-active !important;
    }

    // menu hover'''

assert old in s, 'pattern not found'
s = s.replace(old, new)
io.open(p, 'w', encoding='utf-8', newline='').write(s)
print('sidebar.scss patched')
