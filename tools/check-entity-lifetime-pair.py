#!/usr/bin/env python3
"""Check actual same-jar positive/negative witnesses for removed cached sky entities.

This is a causal engineering check, not an independent final audit. It uses raw
client instance state recorded before the production guard, not a desired count
as a screenshot-readiness condition. Different TAA jitters are declared, not
misrepresented as a pixel-identical A/B. All outputs are exclusive new files.
"""
from __future__ import annotations
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import sys
import zipfile
import numpy as np
from PIL import Image

ROOT=Path(__file__).resolve().parent.parent
SCENES=("dim-overworld-day","trans-open-none-none-empty","trans-sky-none-none-empty","trans-removed-none")
OLD={(x,y,2) for x in range(-5,6) for y in range(181,191)}
NEW={(x,y,5) for x in range(-5,6) for y in range(140,148)}
PACK_SHA="334d0b0dadb5e2b927b992f2b93596c4cc1a518305c1e2a9b175c754a4d946e8"


def digest(path):
    h=hashlib.sha256()
    with Path(path).open("rb") as f:
        for block in iter(lambda:f.read(1024*1024),b""): h.update(block)
    return h.hexdigest()


def coord(row): return row["x"],row["y"],row["z"]


def old_attempts(meta):
    return [row for row in meta["blockCollection"]["collectAttempts"] if coord(row) in OLD]


def removed_witness(rows):
    return ({coord(row) for row in rows}==OLD and all(
        row.get("sameLevel") is True and row.get("isRemoved") is True
        and row.get("cachedSkyBlock") is True and row.get("currentChunkAvailable") is True
        and row.get("currentBlock")=="minecraft:air" and row.get("rawMapSameEntity") is False
        and row.get("afterOpaque") is False for row in rows))


def phase(meta):
    sky=meta["displaySky"]
    return {"dimension":meta["dimension"],"camera":meta["camera"],
            "projection":meta["inverseDisplayViewProjection"],"rasterScale":meta["rasterScale"],
            "timeSource":sky["timeSource"],"phase":sky["phase"],"moonPhase":sky["moonPhase"],
            "rain":sky["rain"],"thunder":sky["thunder"]}


class Fixture:
    def __init__(self,path,legacy,pins,add):
        self.root=Path(path).resolve(strict=True); self.pins=pins
        self.root.relative_to(ROOT)
        self.manifest=self.json(self.root/"fixture.json")
        self.run=self.json(self.root/"run-result.json")
        self.pin(self.root/"client.log")
        add(str(self.root)+":finished-runtime",self.run.get("finished") is True and self.run.get("exitCode")==0
            and self.run.get("fatalErrors")==[] and self.run.get("renderThreadViolations")==[])
        m=self.manifest
        add(str(self.root)+":explicit-roles",m.get("profile")=="iris" and m.get("suite")=="all"
            and m.get("requestedScenes")==list(SCENES) and m.get("collectionDiagnostics") is True
            and m.get("negativeEntityValidity") is legacy and m.get("negativeFixtureStartup") is False
            and m.get("initialRenderDistance")==12 and m.get("nativeTransparencyControl") is False)
        add(str(self.root)+":no-other-negative",not any(value is True for key,value in m.items()
            if key.startswith("negative") and key!="negativeEntityValidity"))
        jar=Path(m["jar"]).resolve(strict=True); jar.relative_to(ROOT)
        self.jar_hash=self.pin(jar)
        add(str(self.root)+":frozen-jar-match",self.jar_hash==m["jarSha256"])
        mods={}
        for installed in sorted((self.root/"mods").glob("*.jar")):
            value=self.pin(installed)
            with zipfile.ZipFile(installed) as z:
                metadata=json.loads(z.read("fabric.mod.json"))
            identifier=metadata["id"]
            if identifier in mods: raise ValueError("Duplicate installed mod identity")
            mods[identifier]=value
        add(str(self.root)+":installed-main-match",mods.get("skyboxblock")==self.jar_hash)
        pack=self.root/"shaderpacks"/m["shader"]
        pack_hash=self.pin(pack)
        add(str(self.root)+":exact-itrp-pack",pack_hash==PACK_SHA)
        settings_file=pack.with_name(pack.name+".txt")
        actual_settings=settings_file.read_text(encoding="utf-8") if settings_file.exists() else None
        if settings_file.exists(): self.pin(settings_file)
        # Iris removes an all-default options file. Only the exact-pack semantic defaults may replace it.
        self.pin(self.root/"config/iris.properties")
        spec=importlib.util.spec_from_file_location("entity_lifetime_settings",ROOT/"tools/check-portal-regression.py")
        settings=importlib.util.module_from_spec(spec); sys.modules[spec.name]=settings; spec.loader.exec_module(settings)
        declared="\n".join(k+"="+str(v) for k,v in m["shaderOptions"].items())
        actual=settings.settings_evidence(dict(shader=m["shader"],shaderSha256=pack_hash,settings=declared),
            actual_settings,pack_hash)
        add(str(self.root)+":actual-settings-match",actual["matched"])
        iris=settings.properties((self.root/"config/iris.properties").read_text(encoding="utf-8"))
        add(str(self.root)+":actual-shaders-active",iris.get("enableShaders","").lower()=="true" and iris.get("shaderPack")==m["shader"])
        self.signature={"jarSha256":self.jar_hash,"mods":mods,"shaderSha256":pack_hash,
                        "effectiveSettings":actual["actualEffective"],"freezeShaderTime":m["freezeShaderTime"],"freezeClouds":m["freezeClouds"]}
        self.scenes={name:self.json(self.root/"results"/(name+".json")) for name in SCENES}
        for name,d in self.scenes.items():
            add(name+(":negative" if legacy else ":owned")+":startup-real-precondition",
                d["fixtureStartup"].get("legacyWithoutStartupPrecondition") is False
                and d["fixtureStartup"].get("clientExistingChunks")==4
                and d["fixtureStartup"].get("serverExistingChunks")==4
                and d["fixtureStartup"].get("compiledTargetSectionsRequired") is True
                and d["fixtureStartup"].get("compiledFixtureSections")==4
                and d["fixtureStartup"].get("stableClientTicks",0)>=20)
            self.pin(self.root/"results"/(name+".png"))

    def pin(self,path):
        path=Path(path).resolve(strict=True); path.relative_to(ROOT)
        value=digest(path); self.pins[str(path)]={"sha256":value,"bytes":path.stat().st_size}; return value

    def json(self,path):
        self.pin(path); return json.loads(Path(path).read_text(encoding="utf-8"))

    def image(self,name,suffix):
        path=self.root/"results"/(name+suffix+".png"); self.pin(path)
        with Image.open(path) as image: return np.asarray(image.convert("RGB"))


def evaluate(owned,negative):
    checks=[]; pins={}
    def add(name,passed,**data): checks.append(dict(name=name,passed=bool(passed),**data))
    add("independent-runtime-directories",Path(owned).resolve()!=Path(negative).resolve())
    a,b=Fixture(owned,False,pins,add),Fixture(negative,True,pins,add)
    add("same-actual-artifact-mods-pack-and-settings",a.signature==b.signature)
    summaries=[]
    for name in SCENES:
        x,y=a.scenes[name],b.scenes[name]
        add(name+":actual-pose-phase-and-raster-match",phase(x)==phase(y))
        add(name+":no-late-collection",x["blockCollection"]["collectAttemptsAfterOpaque"]==0
            and y["blockCollection"]["collectAttemptsAfterOpaque"]==0)
        expected=OLD if name==SCENES[0] else NEW if name==SCENES[2] else set()
        actual={coord(row) for row in x["blockCollection"]["atEnd"]["collectedPositions"]}
        add(name+":owned-exact-live-positions",actual==expected,actual=len(actual),expected=len(expected))
        if name==SCENES[0]:
            add(name+":both-original-live-walls-kept",x["blocks"]==y["blocks"]==110
                and x["depthChangedPixels"]>0 and y["depthChangedPixels"]>0)
        else:
            ax,by=old_attempts(x),old_attempts(y)
            add(name+":owned-real-removed-instance-witness",removed_witness(ax),uniquePositions=len({coord(z) for z in ax}))
            add(name+":negative-real-removed-instance-witness",removed_witness(by),uniquePositions=len({coord(z) for z in by}))
            negative_positions={coord(row) for row in y["blockCollection"]["atEnd"]["collectedPositions"]}
            add(name+":negative-actually-retains-old-geometry",negative_positions==expected|OLD
                and y["blocks"]==len(expected|OLD) and y["itrpBackgroundInjected"] is True)
            if expected:
                add(name+":new-wall-not-erased",x["blocks"]==88 and x["vertices"]>0
                    and x["depthChangedPixels"]>0 and x["itrpBackgroundInjected"] is True)
            else:
                depth=a.image(name,"-depth-change")
                before=a.image(name,"-before"); opaque=a.image(name,"-opaque")
                add(name+":zero-private-geometry-and-writes",x["blocks"]==0 and x["vertices"]==0
                    and x["depthChangedPixels"]==0 and not np.any(depth)
                    and np.array_equal(before,opaque) and x["itrpMaterialWrites"] is False
                    and x["itrpBackgroundInjected"] is False and x["itrpInjectionCount"]==0
                    and x["finalResolved"] is False)
                add(name+":actual-new-target-absent",x["transparency"]["skyPresent"] is False
                    and x["transparency"]["serverSkyBlockCount"]==0)
        summaries.append(dict(scene=name,ownedBlocks=x["blocks"],negativeBlocks=y["blocks"],
            ownedVertices=x["vertices"],negativeVertices=y["vertices"],
            ownedBridge=x["itrpBackgroundInjected"],negativeBridge=y["itrpBackgroundInjected"],
            ownedJitter=x["rasterJitter"],negativeJitter=y["rasterJitter"]))
    return dict(kind="entity-lifetime-causal-pair",passed=all(c["passed"] for c in checks),
        passedChecks=sum(c["passed"] for c in checks),totalChecks=len(checks),checks=checks,
        jarSha256=a.jar_hash,owned=str(a.root),negative=str(b.root),scenes=summaries,inputs=pins,
        scope="Same-jar real instance-ownership witnesses; different TAA jitters; not a pixel-identical A/B or independent final audit")


def selftest():
    base=[dict(x=x,y=y,z=z,sameLevel=True,isRemoved=True,cachedSkyBlock=True,currentChunkAvailable=True,
               currentBlock="minecraft:air",rawMapSameEntity=False,afterOpaque=False) for x,y,z in sorted(OLD)]
    cases=[("complete-real-state-model",base,True),("duplicate-attempts-are-not-extra-blocks",base+base,True),
           ("missing-one-instance",base[:-1],False),("no-witness",[],False)]
    for field,value in (("sameLevel",False),("isRemoved",False),("cachedSkyBlock",False),
                        ("currentChunkAvailable",False),("currentBlock","skyboxblock:sky_block"),
                        ("rawMapSameEntity",True),("afterOpaque",True)):
        rows=copy.deepcopy(base); rows[0][field]=value; cases.append(("reject-"+field,rows,False))
    passed=0
    for name,rows,expected in cases:
        good=removed_witness(rows)==expected; passed+=good; print("PASS" if good else "FAIL",name)
    print(f"{passed}/{len(cases)} synthetic witness contracts; not runtime acceptance")
    return 0 if passed==len(cases) else 1


def main():
    p=argparse.ArgumentParser(description=__doc__); p.add_argument("owned",nargs="?",type=Path)
    p.add_argument("negative",nargs="?",type=Path); p.add_argument("--output",type=Path); p.add_argument("--selftest",action="store_true")
    args=p.parse_args()
    if args.selftest: return selftest()
    if args.owned is None or args.negative is None or args.output is None: p.error("Supply owned, negative and --output")
    try: report=evaluate(args.owned,args.negative)
    except (KeyError,ValueError,TypeError,OSError,zipfile.BadZipFile) as error:
        report=dict(kind="entity-lifetime-causal-pair",passed=False,status="NOT_VERIFIED",error=str(error))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    with args.output.open("x",encoding="utf-8") as f: json.dump(report,f,indent=2,allow_nan=False)
    print(args.output.resolve()); print(report.get("passedChecks",0),"/",report.get("totalChecks",0),"PASS" if report["passed"] else "FAIL")
    for check in report.get("checks",[]):
        if not check["passed"]: print("FAIL",check)
    if "error" in report: print(report["error"])
    return 0 if report["passed"] else 1


if __name__=="__main__": raise SystemExit(main())
