#!/usr/bin/env python3
"""TEMP-only contract tests. Simulated reports/audits are NOT real release evidence."""
from __future__ import annotations
import argparse
import copy
from datetime import datetime,timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import shutil
import tempfile
import time
import zipfile

spec=importlib.util.spec_from_file_location("acceptance_gate",Path(__file__).with_name("verify-test5-acceptance.py"))
v=importlib.util.module_from_spec(spec);spec.loader.exec_module(v)


def save(root,name,obj):
    path=root/name;path.parent.mkdir(parents=True,exist_ok=True)
    path.write_text(json.dumps(obj,sort_keys=True,allow_nan=False),encoding="utf-8")
    return dict(path=name,sha256=v.hash_file(path))


def report(root,name,names,**fields):
    checks=[dict(name=n,passed=True) if isinstance(n,str) else n for n in names]
    return save(root,name,dict(checks=checks,passed=all(c["passed"] for c in checks),
        passedChecks=sum(c["passed"] for c in checks),totalChecks=len(checks),**fields))


def build(root):
    m=dict(schemaVersion=1,projectAuthor="Bing6512",releaseId="TEMP-test.5-contract",
        producerSessionId="TEMP-producer",artifacts={},fixtures={},environments={},positionControls={},
        portal=dict(noShader={},experiments=[]),selectedEvidence=[],independentAudit=None)
    for key,identifier in (("jar","skyboxblock"),("harnessJar","skybox_ip_fixture"),("sourceJar",None)):
        path=root/"artifacts"/(key+".jar");path.parent.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(path,"w") as archive:
            if identifier:archive.writestr("fabric.mod.json",json.dumps(dict(id=identifier,authors=["Bing6512"],version="TEMP")))
            else:archive.writestr("dev/yg/TEMP.java","// Synthetic source; project author Bing6512\n")
        m["artifacts"][key]=dict(path=path.relative_to(root).as_posix(),sha256=v.hash_file(path))
    shader=root/"artifacts/shader.zip";shader.write_bytes(b"TEMP shader archive")
    def fixture(key,profile="vanilla",main="candidate",harness="absent",**flags):
        directory="fixtures/"+key;path=root/directory
        (path/"mods").mkdir(parents=True)
        for policy,artifact in ((main,"jar"),(harness,"harnessJar")):
            if policy=="candidate":shutil.copy2(root/m["artifacts"][artifact]["path"],path/"mods"/(artifact+".jar"))
        manifest=dict(profile=profile,shader=None if profile=="vanilla" else "shader.zip",
            jarSha256=m["artifacts"]["jar"]["sha256"] if main=="candidate" else None,
            skyboxblockPresent=main=="candidate",nativeTransparencyControl=False,negativeSeams=False,suite="all",
            negativeFixtureClock=False,negativeCloudBuffer=False,negativeEntityValidity=False,negativeFixtureStartup=False,negativeGlobalFluidFreeze=False,requestedScenes=[])
        manifest.update(flags)
        if harness=="candidate":manifest["harnessSha256"]=m["artifacts"]["harnessJar"]["sha256"]
        if manifest["shader"]:
            (path/"shaderpacks").mkdir();shutil.copy2(shader,path/"shaderpacks/shader.zip")
            manifest["shaderSha256"]=v.hash_file(shader)
        save(root,directory+"/fixture.json",manifest)
        save(root,directory+"/run-result.json",dict(exitCode=0,finished=True,scenes=113,fatalErrors=[],renderThreadViolations=[],
            errors=["model synthetic warning"] if key=="vanilla" else []))
        (path/"client.log").write_text("TEMP synthetic run metadata only",encoding="utf-8")
        for i in range(8):save(root,directory+f"/results/align-{i}.json",dict(width=1280,synthetic=True))
        m["fixtures"][key]=dict(directory=directory,mainMod=main,harnessMod=harness)
        return directory+"/results"
    for env in v.ENVS:
        results=fixture(env,v.PROFILES[env])
        roles={"world":["sky:final-display-matches-independent-sky","sky:sky-interior-complete"],
          "time":["all-real-dimension-scenes-present","nether:visible-day-night-difference","end:visible-day-night-difference"],
          "alignment":["all-nine-alignment-scenes-captured"]+[f"align-{i}:item-{j}-at-correct-projected-location" for i in range(8) for j in range(2)]
                      +[f"align-{i}:line-pixels-at-correct-projected-location" for i in range(8)]}
        m["environments"][env]={}
        for role,names in roles.items():
            ref=report(root,f"reports/{env}-{role}.json",names,directory=results);ref["fixture"]=env
            m["environments"][env][role]=ref
        ref=report(root,f"reports/{env}-transparency.json",[f"pair:{i}:no-transmitted-marker" for i in range(12)],
            directory=results,markerMetricVersion=2);ref["fixture"]=env
        m["environments"][env]["transparency"]=dict(strict=ref)
        derived="derived/"+env
        controls=[]
        for kind,count in (("line-shift-20-raster-px",8),("item-image-shift-60-display-px",16)):
            for p in (root/results).glob("align-*.json"):
                target=root/derived/kind/p.name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,target)
            controls.append(dict(name=kind,passed=True,expectedPositionFailures=count,actualPositionFailures=count,
                metadataByteIdentical=True,allLogicalStateChecksStillPass=True))
        ref=report(root,f"reports/{env}-positions.json",controls,source=results,syntheticDerivedImageControls=True)
        m["positionControls"][env]=dict(report=ref,fixture=env,derivedDirectory=derived)
    for key,negative in (("scan-positive",False),("scan-negative",True)):
        results=fixture(key,"full",negativeSeams=negative)
        stages=[dict(complete=True,jitterCount=23,jitterStates=[dict(key=str(j)) for j in range(23)]) for _ in range(64)]
        save(root,results+"/seamscan/seamscan-summary.json",dict(finished=True,phaseComplete=True,plannedStages=64,
            completedStages=64,negativeSeams=negative,stages=stages))
    negative=dict(directory="fixtures/scan-negative/results/seamscan",negativeSeams=True,passed=True)
    scan=report(root,"reports/seams.json",["synthetic-paired-scan"],directory="fixtures/scan-positive/results/seamscan",
        negativeSeams=False,negativeControl=negative,negativeControlValid=True,sourceReviewRequired=True,
        negativeVisualReproductionFound=False)
    manual=report(root,"reports/manual-source.json",["synthetic-source-review"],kind="manual-seam-source-review",
        reviewed=True,reviewer="TEMP simulated reviewer",findings="TEMP observation, not a real GPU claim",
        candidateJarSha256=m["artifacts"]["jar"]["sha256"],scanReportSha256=scan["sha256"],sourceReviewRequiredObserved=True)
    m["seams"]=dict(positive="scan-positive",negative="scan-negative",report=scan,sourceReview=manual)
    for env in ("no-shader","iris-native","iris-fsr"):
        ids=[]
        for variant in ("baseline","empty","sky"):
            key="portal-"+env+"-"+variant;ids.append(key)
            fixture(key,"vanilla" if env=="no-shader" else "iris",
                "absent" if variant=="baseline" else "candidate","candidate",variant=variant)
        ref=report(root,"reports/portal-"+env+".json",[dict(name="synthetic-portal",passed=env=="no-shader")],baseCombinationPassed=env=="no-shader")
        case=dict(report=ref,fixtures=ids)
        if env=="no-shader":m["portal"]["noShader"]=case
        else:m["portal"]["experiments"].append(dict(environment=env,status="unsupported",supported=False,reason="TEMP explicit boundary",**case))
    m["packageReview"]=report(root,"reports/package.json",["mod-id-and-version","production-has-no-world-color-readback",
        "real-refmap-is-packaged",dict(name="source-distribution-exists",passed=True,path=m["artifacts"]["sourceJar"]["path"])],
        jar=m["artifacts"]["jar"]["path"],sha256=m["artifacts"]["jar"]["sha256"])
    image=root/"evidence.png";image.write_bytes(b"TEMP selected evidence bytes")
    m["selectedEvidence"]=[dict(path="evidence.png",sha256=v.hash_file(image))]
    return m


def rewrite(root,ref,mutate):
    value=json.loads((root/ref["path"]).read_text(encoding="utf-8"));mutate(value)
    checks=value.get("checks")
    if isinstance(checks,list):value.update(passed=all(c["passed"] for c in checks),passedChecks=sum(c["passed"] for c in checks),totalChecks=len(checks))
    ref.update(save(root,ref["path"],value))


SETTLED_WARMUPS=("warmup-empty-front","warmup-empty-oblique")
FORMAL_TRANSPARENCY=tuple(f"trans-{slot}-{front}-{protection}-{state}"
    for slot in ("open","sky") for front in ("none","glass","water")
    for protection in ("none","pillar","hand","both") for state in ("empty","marker")) \
    + tuple("trans-removed-"+front for front in ("none","glass","water"))


def settled_transparency(root,m,env="full-fsr",static=False):
    """Exact 53-scene protocol in a fresh TEMP fixture, never real GPU evidence."""
    source=root/m["fixtures"][env]["directory"];key="settled-"+env;directory="fixtures/"+key;target=root/directory
    assert target.is_relative_to(root) and not target.exists()
    # Do not carry the core builder's align JSON into the exact top-level set.
    shutil.copytree(source,target,ignore=shutil.ignore_patterns("results"))
    manifest=json.loads((target/"fixture.json").read_text(encoding="utf-8"))
    manifest.update(suite="transparency",requestedScenes=[],settledTransparency=True,
        fixedHistoryWarmupScenes=list(SETTLED_WARMUPS),freezeWaterTextures=True,
        waterSpritePhase="first-declared-resource-frame")
    save(root,directory+"/fixture.json",manifest)
    run=json.loads((target/"run-result.json").read_text(encoding="utf-8"));run["scenes"]=53
    save(root,directory+"/run-result.json",run)
    for name in SETTLED_WARMUPS:
        save(root,directory+"/results/"+name+".json",dict(scene=name,synthetic=True,blocks=0,vertices=0,
            itrpMaterialWrites=False,itrpBackgroundInjected=False,finalResolved=False))
    for name in FORMAL_TRANSPARENCY:
        save(root,directory+"/results/"+name+".json",dict(scene=name,synthetic=True,
            fixtureSettlement=dict(requiredMilliseconds=15000,requiredFrames=300,observedMilliseconds=15000,
                observedFrames=300,stablePoseRequired=True),fixtureInputGuardTicks=1,
            observedFixtureWaterSprites=2,frozenFixtureWaterSprites=0 if static else 2,
            suppressedFixtureWaterSpriteTicks=0 if static else 10,staticFixtureWaterSprites=2 if static else 0))
    m["fixtures"][key]=dict(directory=directory,mainMod="candidate",harnessMod="absent")
    ref=m["environments"][env]["transparency"]["strict"];ref["fixture"]=key
    rewrite(root,ref,lambda value:value.update(directory=directory+"/results"))
    return target


def mutate_saved(path,mutation):
    data=json.loads(path.read_text(encoding="utf-8"));mutation(data)
    path.write_text(json.dumps(data,sort_keys=True,allow_nan=False),encoding="utf-8")


def add_calibration(root,m):
    env="full-fsr";strict=m["environments"][env]["transparency"]["strict"]
    failures=[dict(name=name,passed=False,mae=.8,p99=8) for name in sorted(v.ALLOWED_REMOVED)]
    rewrite(root,strict,lambda r:r["checks"].extend(failures))
    controls=[]
    for key in ("repeat-a","repeat-b"):
        src=root/m["fixtures"][env]["directory"];dest=root/"fixtures"/key;shutil.copytree(src,dest)
        mf=json.loads((dest/"fixture.json").read_text());mf["nativeTransparencyControl"]=True;save(root,"fixtures/"+key+"/fixture.json",mf)
        m["fixtures"][key]=dict(directory="fixtures/"+key,mainMod="candidate",harnessMod="absent")
        controls.append(key)
    def subject(key):
        mf=root/m["fixtures"][key]["directory"]
        return dict(directory=m["fixtures"][key]["directory"]+"/results",manifestSha256=v.hash_file(mf/"fixture.json"),
            jarSha256=m["artifacts"]["jar"]["sha256"],captureJitterBits="exact-float-bits",shaderArchiveSha256=v.hash_file(root/"artifacts/shader.zip"),
            inputSettingsSha256="1"*64,freezeShaderTime=True)
    checks=[dict(name="no-other-strict-failure-is-waived",passed=True,originalFailedChecks=[c["name"] for c in failures])]
    for front in ("none","glass","water"):
        budget=dict(mae=1.,p99=9.,absoluteMaeCap=1.5,absoluteP99Cap=10.,lowpassMae=1.,lowpassP99=2.,lowpassMaximum=4.)
        checks.extend([dict(name=front+":native-controls-stable-under-hard-caps",passed=True,budget=budget),
            dict(name=front+":candidate-restoration-within-native-budget",passed=True,budget=budget,
                metrics=dict(raw=dict(mae=.8,p99=8.),lowpass=dict(mae=.1,p99=1.,maximum=2.)))])
    current=json.loads((root/strict["path"]).read_text())
    m["environments"][env]["transparency"]["calibrated"]=report(root,"reports/calibrated.json",checks,
        kind="native-repeat-calibrated-restoration",candidate=subject(env),controls=[subject(k) for k in controls],
        strictReportSha256=strict["sha256"],strictPassedChecks=current["passedChecks"],strictTotalChecks=current["totalChecks"],note="TEMP independently bounded native budget")


def audit(root,m):
    save(root,"matrix.json",m)
    pre=v.verify(root/"matrix.json",project_root=root,prepare_audit=True)
    fields={"kind":"independent-final-audit","independent":True,"freshReview":True,"reviewed":True,
        "verifiedReportBindings":True,"reviewedWarnings":True,"projectAuthor":"Bing6512",
        "reviewer":"TEMP simulated independent reviewer","reviewerSessionId":"TEMP-independent-session",
        "reviewedAt":datetime.now(timezone.utc).isoformat(),"auditSubjectSha256":pre["auditSubjectSha256"]}
    for name,h in (("jar","jarSha256"),("sourceJar","sourceJarSha256"),("harnessJar","harnessSha256")):fields[h]=m["artifacts"][name]["sha256"]
    m["independentAudit"]=report(root,"reports/audit.json",["TEMP-simulated-review"],**fields)
    save(root,"matrix.json",m)
    return pre


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output",type=Path,help="Create a new TEMP-only engineering summary; existing files are preserved")
    args=parser.parse_args(argv)
    started=time.monotonic();results=[];controls=[]
    source_pins={str(path.resolve()):v.hash_file(path.resolve()) for path in (Path(__file__),Path(v.__file__))}
    with tempfile.TemporaryDirectory(prefix="test5-acceptance-selftest-") as directory:
        top=Path(directory).resolve()
        assert top.parent==Path(tempfile.gettempdir()).resolve() and top.name.startswith("test5-acceptance-selftest-")
        def run(name,mutation=None,expected=False,calibrated=False,audit_mutation=None,no_audit=False,require_failure=None):
            root=top/("case-%02d"%len(results));root.mkdir();m=build(root)
            if calibrated:add_calibration(root,m)
            if mutation:mutation(root,m)
            pre=audit(root,m)
            if audit_mutation:rewrite(root,m["independentAudit"],audit_mutation);save(root,"matrix.json",m)
            if no_audit:m["independentAudit"]=None;save(root,"matrix.json",m)
            result=v.verify(root/"matrix.json",project_root=root)
            failures=[c for c in result["checks"] if not c["passed"]]
            ok=result["passed"] is expected and (require_failure is None or any(require_failure in c["name"] for c in failures))
            controls.append(dict(name=name,passed=ok,expectedGatePass=expected,actualGatePass=result["passed"],
                requiredFailureFragment=require_failure,failedGateChecks=failures))
            results.append(ok);print(("PASS " if ok else "FAIL ")+name,flush=True)
            if not ok:print(json.dumps([c for c in result["checks"] if not c["passed"]],indent=2))
            return root,m,result,pre
        root,m,result,pre=run("valid-TEMP-contract-only",expected=True)
        assert not pre["passed"] and pre["readyForIndependentAudit"] and result["seams"]["sourceReviewRequired"]
        assert next(f for f in result["fixtures"] if f["id"]=="vanilla")["errors"]
        assert result["supportBoundaries"]["irisPortalSupported"] is False
        run("bounded-native-companion-retains-strict-failures",expected=True,calibrated=True)
        def limited_basic(r,m):
            e=m["portal"]["experiments"][0]
            rewrite(r,e["report"],lambda x:(x["checks"][0].update(passed=True),x.update(baseCombinationPassed=True)))
            e.update(supported=True,status="limited-basic",reason="TEMP basic translation only; rotation and nesting untested")
        run("real-passed-Iris-basic-not-forced-unsupported",limited_basic,expected=True)
        def controlled_distant(r,m,plan="DISABLED",renderer="DEFAULT"):
            root=r/"fixtures/full-fsr";config=root/"config/DistantHorizons.toml";config.parent.mkdir(parents=True,exist_ok=True)
            config.write_text('[common.worldGenerator]\ngeneratorPlan="'+plan+'"\n[client.advanced.debugging]\nrendererMode="'+renderer+'"\n',encoding="utf-8")
            with zipfile.ZipFile(root/"mods/TEMP-dh.jar","w") as jar:
                jar.writestr("fabric.mod.json",json.dumps(dict(id="distanthorizons",version="TEMP")))
            manifest=json.loads((root/"fixture.json").read_text());manifest["distantGenerationPolicy"]=dict(requested="disabled",actualGeneratorPlan="DISABLED",rendererModeUnchanged="DEFAULT",limitation="TEMP controlled near-world only; dynamic LOD not validated")
            save(r,"fixtures/full-fsr/fixture.json",manifest)
        _,_,controlled,_=run("controlled-near-world-is-explicitly-warned",controlled_distant,expected=True)
        assert any(w["kind"]=="controlled-near-world-not-dynamic-DH-validation" for w in controlled["warnings"])
        run("claimed-worldgen-policy-must-match-real-config",lambda r,m:controlled_distant(r,m,plan="SURFACE_THEN_CHUNKS"))
        run("turning-off-DH-renderer-cannot-masquerade-as-controlled-generation",lambda r,m:controlled_distant(r,m,renderer="DISABLED"))
        def warmed_transparency(r,m):
            source=r/"fixtures/full-fsr";target=r/"fixtures/warmed-trans";shutil.copytree(source,target)
            warm=["align-solid-no-sky","align-clean"]
            measured=[f"trans-{slot}-{front}-{protection}-{state}" for slot in ("open","sky") for front in ("none","glass","water") for protection in ("none","pillar","hand","both") for state in ("empty","marker")]+["trans-removed-"+front for front in ("none","glass","water")]
            manifest=json.loads((target/"fixture.json").read_text());manifest.update(suite="all",fixedHistoryWarmupScenes=warm,requestedScenes=warm+measured)
            save(r,"fixtures/warmed-trans/fixture.json",manifest)
            for name in warm+measured:save(r,"fixtures/warmed-trans/results/"+name+".json",dict(scene=name,blocks=0,vertices=0,itrpMaterialWrites=False,itrpBackgroundInjected=False,finalResolved=False,synthetic=True))
            m["fixtures"]["warmed-trans"]=dict(directory="fixtures/warmed-trans",mainMod="candidate",harnessMod="absent")
            ref=m["environments"]["full-fsr"]["transparency"]["strict"];ref["fixture"]="warmed-trans"
            rewrite(r,ref,lambda x:x.update(directory="fixtures/warmed-trans/results"))
        run("complete-51-plus-two-sky-free-warmups-allowed-only-for-transparency",warmed_transparency,expected=True)
        def warmed_bad(r,m):
            warmed_transparency(r,m);save(r,"fixtures/warmed-trans/results/align-clean.json",dict(blocks=1,vertices=36,itrpMaterialWrites=True,itrpBackgroundInjected=True,finalResolved=True))
        run("sky-containing-warmup-cannot-pass",warmed_bad)
        def warmed_partial(r,m):
            warmed_transparency(r,m);p=r/"fixtures/warmed-trans/fixture.json";d=json.loads(p.read_text());d["requestedScenes"].pop();save(r,"fixtures/warmed-trans/fixture.json",d)
        run("warmed-protocol-must-retain-all-51-measurements",warmed_partial)
        def warmed_as_core(r,m):
            warmed_transparency(r,m);m["environments"]["full-fsr"]["world"]["fixture"]="warmed-trans"
        run("53-warmed-transparency-not-a-113-core-model",warmed_as_core)
        run("fake-hash-rejected",lambda r,m:m["artifacts"]["jar"].update(sha256="0"*64))
        run("invalid-hash-format-rejected",lambda r,m:m["artifacts"]["sourceJar"].update(sha256="fake"))
        def change_copied_jar(r,m):
            with zipfile.ZipFile(r/"fixtures/vanilla/mods/jar.jar","a") as jar:jar.writestr("TEMP-tampered.class",b"changed copied bytes")
        run("copied-fixture-mods-jar-rehashed",change_copied_jar)
        run("report-hash-mismatch-rejected",lambda r,m:(r/m["environments"]["vanilla"]["world"]["path"]).write_text("{}"))
        run("report-wrong-fixture-rejected",lambda r,m:rewrite(r,m["environments"]["vanilla"]["world"],lambda x:x.update(directory="fixtures/iris-native/results")))
        run("world-report-not-alignment-evidence",lambda r,m:m["environments"]["vanilla"].update(alignment=copy.deepcopy(m["environments"]["vanilla"]["world"])))
        run("experimental-failure-not-compatible-pass",lambda r,m:m["portal"]["experiments"][0].update(supported=True,status="supported"))
        run("missing-independent-audit",no_audit=True)
        run("engineering-selftest-is-not-independent-audit",audit_mutation=lambda x:x.update(kind="test5-engineering-selftest"))
        run("audit-must-be-fresh",audit_mutation=lambda x:x.update(freshReview=False))
        run("audit-must-be-independent-session",audit_mutation=lambda x:x.update(reviewerSessionId="TEMP-producer"))
        run("stale-subject-audit-rejected",audit_mutation=lambda x:x.update(auditSubjectSha256="0"*64))
        run("audit-package-mismatch-rejected",audit_mutation=lambda x:x.update(jarSha256="0"*64))
        run("manual-review-does-not-clear-flag",lambda r,m:rewrite(r,m["seams"]["sourceReview"],lambda x:x.update(sourceReviewRequiredObserved=False)))
        run("negative-seam-pair-actually-required",lambda r,m:save(r,"fixtures/scan-negative/fixture.json",dict(profile="full",shader=None,jarSha256=m["artifacts"]["jar"]["sha256"],negativeSeams=False)))
        run("position-metadata-claim-required",lambda r,m:rewrite(r,m["positionControls"]["vanilla"]["report"],lambda x:x["checks"][0].update(metadataByteIdentical=False)))
        run("actual-position-metadata-rehashed",lambda r,m:save(r,"derived/vanilla/line-shift-20-raster-px/align-0.json",dict(tampered=True)))
        run("fixture-run-exit-must-zero",lambda r,m:save(r,"fixtures/vanilla/run-result.json",dict(exitCode=1,finished=True,fatalErrors=[],renderThreadViolations=[],errors=[])))
        run("render-thread-field-must-exist",lambda r,m:save(r,"fixtures/vanilla/run-result.json",dict(exitCode=0,finished=True,fatalErrors=[],errors=[])))
        run("calibration-cannot-waive-other-failure",lambda r,m:rewrite(r,m["environments"]["full-fsr"]["transparency"]["strict"],lambda x:x["checks"].append(dict(name="unexpected-xray",passed=False))),calibrated=True)
        run("calibration-must-match-strict-sha",lambda r,m:rewrite(r,m["environments"]["full-fsr"]["transparency"]["calibrated"],lambda x:x.update(strictReportSha256="0"*64)),calibrated=True)
        run("calibration-must-be-same-candidate",lambda r,m:rewrite(r,m["environments"]["full-fsr"]["transparency"]["calibrated"],lambda x:x["candidate"].update(jarSha256="0"*64)),calibrated=True)
        run("calibration-hard-caps-not-removable",lambda r,m:rewrite(r,m["environments"]["full-fsr"]["transparency"]["calibrated"],lambda x:x["checks"][1]["budget"].update(absoluteMaeCap=99.)),calibrated=True)
        run("old-highpass-only-report-not-final",lambda r,m:rewrite(r,m["environments"]["vanilla"]["transparency"]["strict"],lambda x:x.update(markerMetricVersion=1)))
        def manifest_flag(r,m,key,value):
            p=r/"fixtures/vanilla/fixture.json";data=json.loads(p.read_text());data[key]=value;save(r,"fixtures/vanilla/fixture.json",data)
        run("R16-clock-negative-not-mandatory-positive",lambda r,m:manifest_flag(r,m,"negativeFixtureClock",True))
        run("R16-cloud-buffer-negative-not-mandatory-positive",lambda r,m:manifest_flag(r,m,"negativeCloudBuffer",True))
        run("stale-entity-negative-not-release-positive",lambda r,m:manifest_flag(r,m,"negativeEntityValidity",True))
        run("legacy-startup-negative-not-release-positive",lambda r,m:manifest_flag(r,m,"negativeFixtureStartup",True))
        run("global-world-freeze-negative-not-release-positive",lambda r,m:manifest_flag(r,m,"negativeGlobalFluidFreeze",True))
        run("single-scene-subset-not-full-release",lambda r,m:manifest_flag(r,m,"requestedScenes",["dim-overworld-day"]))
        assert len(results)==40, "Retain all 40 original TEMP controls before adding the new protocol cases"
        def core_only(r,m,env,role,location=None,field=None,value=None,remove=False):
            key="core-"+env+"-"+role;directory="fixtures/"+key
            source=r/m["fixtures"][env]["directory"];target=r/directory
            assert target.is_relative_to(r) and not target.exists()
            shutil.copytree(source,target)
            m["fixtures"][key]=dict(m["fixtures"][env],directory=directory)
            ref=m["environments"][env][role];ref["fixture"]=key
            rewrite(r,ref,lambda report:report.update(directory=directory+"/results"))
            if location:
                path=target/("fixture.json" if location=="manifest" else "run-result.json")
                mutate_saved(path,lambda data:data.pop(field) if remove else data.__setitem__(field,value))
        for env in v.ENVS:
            for role in ("world","time","alignment"):
                prefix=env+"-"+role
                run(prefix+"-default-all113-core-positive",lambda r,m,e=env,k=role:core_only(r,m,e,k),expected=True)
                for name,location,field,value,remove in (
                    ("wrong-suite","manifest","suite","bugs",False),
                    ("missing-suite","manifest","suite",None,True),
                    ("53-scenes-not-core","run","scenes",53,False),
                    ("112-scenes-not-core","run","scenes",112,False),
                    ("114-scenes-not-default-core","run","scenes",114,False),
                    ("missing-scene-count","run","scenes",None,True)):
                    run(prefix+"-"+name,lambda r,m,e=env,k=role,l=location,f=field,q=value,d=remove:core_only(r,m,e,k,l,f,q,d),
                        require_failure=env+":"+role+":complete-113-default-core")
                run(prefix+"-explicit-subset-not-default-core",lambda r,m,e=env,k=role:core_only(r,m,e,k,"manifest","requestedScenes",["bug-panel-empty"]),
                    require_failure="complete-required-scene-protocol")
        _,_,settled,_=run("exact-settled53-frozen-animated-sprites-positive",settled_transparency,expected=True)
        assert any(w["kind"]=="fixed-static-water-and-settled-transparency-fixture" for w in settled["warnings"])
        run("exact-settled53-static-resource-sprites-positive",lambda r,m:settled_transparency(r,m,static=True),expected=True)
        def settled_manifest(r,m,mutation):
            target=settled_transparency(r,m);mutate_saved(target/"fixture.json",mutation)
        def settled_meta(r,m,name,mutation,static=False):
            target=settled_transparency(r,m,static=static);mutate_saved(target/"results"/(name+".json"),mutation)
        def settled_run(r,m,mutation):
            target=settled_transparency(r,m);mutate_saved(target/"run-result.json",mutation)
        def remove_settled_json(r,m,name):
            target=settled_transparency(r,m);path=(target/"results"/(name+".json")).resolve(strict=True)
            assert path.is_relative_to(r.resolve()) and path.name==name+".json"
            path.unlink()
        protocol="full-fsr:complete-settled-53-scene-protocol"
        water_phase="full-fsr:explicit-static-water-phase"
        for field,value in (("suite","all"),("fixedHistoryWarmupScenes",[]),
                            ("fixedHistoryWarmupScenes",list(reversed(SETTLED_WARMUPS))),
                            ("fixedHistoryWarmupScenes",["warmup-empty-front","unreviewed-warmup"])):
            run("settled53-wrong-"+field+"-"+str(value),lambda r,m,f=field,q=value:settled_manifest(r,m,lambda d:d.__setitem__(f,q)),require_failure=protocol)
        for field in ("suite","fixedHistoryWarmupScenes"):
            run("settled53-missing-"+field,lambda r,m,f=field:settled_manifest(r,m,lambda d:d.pop(f)),require_failure=protocol)
        run("settled53-explicit-requested-subset-blocked",lambda r,m:settled_manifest(r,m,lambda d:d.update(requestedScenes=[SETTLED_WARMUPS[0]])),require_failure="full-fsr:transparency")
        run("settled53-missing-default-requestedScenes-blocked",lambda r,m:settled_manifest(r,m,lambda d:d.pop("requestedScenes")),require_failure="full-fsr:transparency")
        for value in (51,52,54,113):
            run("settled53-wrong-declared-scene-count-"+str(value),lambda r,m,q=value:settled_run(r,m,lambda d:d.update(scenes=q)),require_failure=protocol)
        run("settled53-missing-declared-scene-count",lambda r,m:settled_run(r,m,lambda d:d.pop("scenes")),require_failure=protocol)
        for name in (FORMAL_TRANSPARENCY[0],"trans-sky-water-both-marker",FORMAL_TRANSPARENCY[-1])+SETTLED_WARMUPS:
            run("settled53-declares53-but-missing-"+name,lambda r,m,n=name:remove_settled_json(r,m,n),require_failure=protocol)
        def extra_top(r,m):
            target=settled_transparency(r,m);save(r,target.relative_to(r).as_posix()+"/results/extra-formal.json",dict(synthetic=True))
        run("settled53-extra-top-level-json-not-exact-protocol",extra_top,require_failure=protocol)
        def nested_diagnostic(r,m):
            target=settled_transparency(r,m);save(r,target.relative_to(r).as_posix()+"/results/diagnostics/owned-readonly.json",dict(synthetic=True))
        run("settled53-nested-diagnostic-not-a-top-level-formal-scene",nested_diagnostic,expected=True)
        for field,value in (("freezeWaterTextures",False),("waterSpritePhase","dynamic-current-resource-frame")):
            run("settled53-wrong-"+field,lambda r,m,f=field,q=value:settled_manifest(r,m,lambda d:d.__setitem__(f,q)),require_failure=water_phase)
        for field in ("freezeWaterTextures","waterSpritePhase"):
            run("settled53-missing-"+field,lambda r,m,f=field:settled_manifest(r,m,lambda d:d.pop(f)),require_failure=water_phase)
        for name in SETTLED_WARMUPS:
            for field,value in (("blocks",1),("vertices",36),("itrpMaterialWrites",True),("itrpBackgroundInjected",True),("finalResolved",True)):
                run(name+"-warmup-sky-evidence-"+field,lambda r,m,n=name,f=field,q=value:settled_meta(r,m,n,lambda d:d.__setitem__(f,q)),require_failure=name+":actually-sky-free")
            for field in ("blocks","vertices","itrpMaterialWrites","itrpBackgroundInjected","finalResolved"):
                run(name+"-warmup-missing-"+field,lambda r,m,n=name,f=field:settled_meta(r,m,n,lambda d:d.pop(f)),require_failure=name+":actually-sky-free")
        formal_samples=(FORMAL_TRANSPARENCY[0],"trans-sky-water-both-marker",FORMAL_TRANSPARENCY[-1])
        for name in formal_samples:
            settle_failure=name+":actual-time-and-render-frame-settle"
            sprite_failure=name+":actual-water-sprite-control"
            for field in ("requiredMilliseconds","requiredFrames","observedMilliseconds","observedFrames","stablePoseRequired"):
                run(name+"-missing-settle-"+field,lambda r,m,n=name,f=field:settled_meta(r,m,n,lambda d:d["fixtureSettlement"].pop(f)),require_failure=settle_failure)
            for field,value in (("requiredMilliseconds",14999),("requiredMilliseconds",15001),("requiredFrames",299),("requiredFrames",301),
                                ("observedMilliseconds",14999),("observedFrames",299),("stablePoseRequired",False),("stablePoseRequired",1)):
                run(name+"-wrong-settle-"+field+"-"+str(value),lambda r,m,n=name,f=field,q=value:settled_meta(r,m,n,lambda d:d["fixtureSettlement"].__setitem__(f,q)),require_failure=settle_failure)
            run(name+"-missing-whole-settlement",lambda r,m,n=name:settled_meta(r,m,n,lambda d:d.pop("fixtureSettlement")),require_failure=settle_failure)
            run(name+"-missing-input-guard",lambda r,m,n=name:settled_meta(r,m,n,lambda d:d.pop("fixtureInputGuardTicks")),require_failure=settle_failure)
            for value in (0,-1):
                run(name+"-nonpositive-input-guard-"+str(value),lambda r,m,n=name,q=value:settled_meta(r,m,n,lambda d:d.update(fixtureInputGuardTicks=q)),require_failure=settle_failure)
            for field in ("observedFixtureWaterSprites","frozenFixtureWaterSprites","suppressedFixtureWaterSpriteTicks"):
                run(name+"-missing-frozen-path-"+field,lambda r,m,n=name,f=field:settled_meta(r,m,n,lambda d:d.pop(f)),require_failure=sprite_failure)
                run(name+"-zero-frozen-path-"+field,lambda r,m,n=name,f=field:settled_meta(r,m,n,lambda d:d.__setitem__(f,0)),require_failure=sprite_failure)
            run(name+"-sprite-off-cannot-claim-settled",lambda r,m,n=name:settled_meta(r,m,n,lambda d:d.update(observedFixtureWaterSprites=0,frozenFixtureWaterSprites=0,suppressedFixtureWaterSpriteTicks=0,staticFixtureWaterSprites=0)),require_failure=sprite_failure)
            run(name+"-static-path-missing-static-sprite-counter",lambda r,m,n=name:settled_meta(r,m,n,lambda d:d.pop("staticFixtureWaterSprites"),static=True),require_failure=sprite_failure)
            run(name+"-static-path-unobserved-sprites-even-if-static-positive",lambda r,m,n=name:settled_meta(r,m,n,lambda d:d.update(observedFixtureWaterSprites=0),static=True),require_failure=sprite_failure)
        def beyond_minimum(r,m):
            target=settled_transparency(r,m)
            for name in FORMAL_TRANSPARENCY:
                mutate_saved(target/"results"/(name+".json"),lambda d:d["fixtureSettlement"].update(observedMilliseconds=20000,observedFrames=450))
        run("settled53-observed-time-and-frames-may-exceed-fixed-minima",beyond_minimum,expected=True)
        run("settled53-frozen-path-does-not-invent-required-static-counter",lambda r,m:settled_meta(r,m,FORMAL_TRANSPARENCY[-1],lambda d:d.pop("staticFixtureWaterSprites")),expected=True)
        run("settled53-static-path-does-not-invent-frozen-hook-counters",lambda r,m:settled_meta(r,m,FORMAL_TRANSPARENCY[-1],lambda d:(d.pop("frozenFixtureWaterSprites"),d.pop("suppressedFixtureWaterSpriteTicks")),static=True),expected=True)
        for role in ("world","time","alignment"):
            def settled_as_core(r,m,k=role):
                settled_transparency(r,m);ref=m["environments"]["full-fsr"][k];ref["fixture"]="settled-full-fsr"
                rewrite(r,ref,lambda report:report.update(directory="fixtures/settled-full-fsr/results"))
            run("settled53-not-default113-"+role,settled_as_core,require_failure="full-fsr:"+role+":complete-113-default-core")
        # Type probes are intentionally strict expected failures. If Gate accepts
        # bool/float metadata as integer counters, preserve the red report and
        # hand the validator defect back to its owner; never patch Gate here.
        typed_scene="trans-sky-water-both-marker"
        for value in (None,[],0,"not-an-object"):
            run("settled53-settlement-nonobject-"+repr(value),lambda r,m,q=value:settled_meta(r,m,typed_scene,lambda d:d.update(fixtureSettlement=q)),require_failure="full-fsr:transparency")
        for field,float_value in (("requiredMilliseconds",15000.0),("requiredFrames",300.0),("observedMilliseconds",15000.5),("observedFrames",300.5)):
            for value in (True,False,float_value):
                run("settled53-noninteger-"+field+"-"+repr(value),lambda r,m,f=field,q=value:settled_meta(r,m,typed_scene,lambda d:d["fixtureSettlement"].__setitem__(f,q)),require_failure="full-fsr:transparency")
        for value in (True,False,1.5):
            run("settled53-noninteger-input-guard-"+repr(value),lambda r,m,q=value:settled_meta(r,m,typed_scene,lambda d:d.update(fixtureInputGuardTicks=q)),require_failure="full-fsr:transparency")
        for field in ("observedFixtureWaterSprites","frozenFixtureWaterSprites","suppressedFixtureWaterSpriteTicks","staticFixtureWaterSprites"):
            for value in (True,False,1.5):
                run("settled53-noninteger-sprite-"+field+"-"+repr(value),lambda r,m,f=field,q=value:settled_meta(r,m,typed_scene,lambda d:d.__setitem__(f,q),static=f=="staticFixtureWaterSprites"),require_failure="full-fsr:transparency")
        stable=all(v.hash_file(Path(path))==digest for path,digest in source_pins.items())
        results.append(stable);controls.append(dict(name="selftest-and-gate-source-pins-unchanged-during-run",passed=stable))
        summary=dict(kind="TEMP-only engineering contract selftest; NOT real release evidence or an independent final audit",
            passed=all(results),originalControlsRetained=40,totalChecks=len(results),passedChecks=sum(results),failedChecks=len(results)-sum(results),
            seconds=round(time.monotonic()-started,3),sourceSha256=source_pins,controls=controls,
            testedProtocols=dict(core="default suite=all, requestedScenes=[], run.scenes=113",settledTransparency="two fixed sky-free warmups + complete formal51 =53; original caps unchanged"))
        if args.output:v.write_exclusive(args.output,summary)
        print(f"{sum(results)}/{len(results)} TEMP engineering contract controls passed; NOT an independent final audit.")
        return 0 if all(results) else 1


if __name__=="__main__":raise SystemExit(main())
