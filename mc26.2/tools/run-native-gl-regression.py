#!/usr/bin/env python3
"""Compile/run real hidden GL3.3 and GL4.5 tests against production GLSL and GL scope."""
from pathlib import Path
import os
import subprocess
import sys

PROJECT = Path(__file__).resolve().parents[1]
JAVA = Path(r'C:\Program Files\Java\jdk-25.0.4.1\bin\java.exe')
NATIVES = Path(r'D:\Program Files\Game\Minecraft\PCL\.minecraft\versions\26.2-Fabric 0.20.1\26.2-Fabric 0.20.1-natives\lwjgl')
OUT = PROJECT / 'build' / 'native-gl-regression'
REPORTS = PROJECT / 'build' / 'reports' / 'native-gl'


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    REPORTS.mkdir(parents=True, exist_ok=True)
    cp = str(PROJECT / 'build/classes/java/main') + os.pathsep + str(PROJECT / 'build/resources/main') + os.pathsep + (PROJECT / 'build' / 'compile-classpath.txt').read_text(encoding='utf-8')
    sources = [PROJECT / 'src/main/java/dev/yg/skyboxblock/client/SkyGlScope.java',
               PROJECT / 'src/main/java/dev/yg/skyboxblock/client/SkyWindowMath.java',
               PROJECT / 'src/test/java/dev/yg/skyboxblock/client/SkyGl33Regression.java',
               PROJECT / 'src/test/java/dev/yg/skyboxblock/client/SkyIsolatedGlRegression.java',
               PROJECT / 'src/test/java/dev/yg/skyboxblock/client/SkyItrpSceneInputRegression.java',
               PROJECT / 'src/test/java/dev/yg/skyboxblock/client/SkyItrpDarkMeterRegression.java']
    subprocess.run([str(JAVA.with_name('javac.exe')), '-J-Duser.language=en', '-encoding', 'UTF-8', '-proc:none',
                    '-cp', cp, '-d', str(OUT), *map(str, sources)], cwd=PROJECT, check=True)
    for name in ['SkyGl33Regression', 'SkyIsolatedGlRegression', 'SkyItrpSceneInputRegression', 'SkyItrpDarkMeterRegression']:
        result = subprocess.run([str(JAVA), '--enable-native-access=ALL-UNNAMED',
                                 '-Djava.library.path=' + str(NATIVES), '-Dorg.lwjgl.librarypath=' + str(NATIVES),
                                 '-cp', str(OUT) + os.pathsep + cp,
                                 'dev.yg.skyboxblock.client.' + name], cwd=PROJECT,
                                capture_output=True, text=True, encoding='utf-8', errors='replace')
        (REPORTS / (name + '.txt')).write_text(result.stdout + result.stderr, encoding='utf-8')
        print(result.stdout, end='')
        if result.returncode:
            print(result.stderr, file=sys.stderr)
            raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
