import zipfile
import os

base = r'D:\harness\newapp\base.apk'
dex = r'D:\harness\newapp\dex\classes.dex'
assets = r'D:\harness\newapp\assets'
dst = r'D:\harness\newapp\app-unsigned.apk'

tmp = dst + '.tmp'
with zipfile.ZipFile(base, 'r') as zin:
    with zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED) as zout:
        for item in zin.infolist():
            zout.writestr(item, zin.read(item.filename))
        # add dex (STORED for android)
        with open(dex, 'rb') as f:
            data = f.read()
        zi = zipfile.ZipInfo('classes.dex')
        zi.compress_type = zipfile.ZIP_STORED
        zout.writestr(zi, data)
        print('added classes.dex', len(data))
        # add assets
        for root, dirs, files in os.walk(assets):
            for f in files:
                path = os.path.join(root, f)
                rel = os.path.join('assets', os.path.relpath(path, assets)).replace('\\', '/')
                with open(path, 'rb') as fh:
                    zout.writestr(rel, fh.read())
                print('added', rel)

os.replace(tmp, dst)
print('app-unsigned:', os.path.getsize(dst))
