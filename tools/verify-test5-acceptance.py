#!/usr/bin/env python3
"""Engineering release-matrix gate, NOT the independent final audit.

python -B tools/verify-test5-acceptance.py --example
python -B tools/verify-test5-acceptance.py MATRIX --subject-digest
python -B tools/verify-test5-acceptance.py MATRIX --output NEW_ACCEPTANCE.json

All paths are PROJECT-relative or absolute beneath PROJECT. FileRef={path,sha256};
report refs additionally name fixture where required. Reports must be an object
or a ONE-element list, never an implicitly selected member of a larger list.

Matrix v1: projectAuthor='Bing6512', releaseId, producerSessionId,
artifacts:{jar,sourceJar,harnessJar}:FileRef,
fixtures:{id:{directory,mainMod:'candidate'|'absent',harnessMod:'candidate'|'absent'}},
environments:{vanilla|iris-native|full-fsr:{world,time,alignment:ReportRef,
 transparency:{strict:ReportRef,calibrated?:FileRef}}},
seams:{positive:fixtureId,negative:fixtureId,report:FileRef,sourceReview:FileRef},
positionControls:{ENV:{report:FileRef,fixture:fixtureId,derivedDirectory}},
portal:{noShader:{report:FileRef,fixtures:[baseline,empty,sky]},
 experiments:[{environment:'iris-native'|'iris-fsr',status:'unsupported'|
 'limited-basic'|'supported-basic',supported:bool,reason,report:FileRef,
 fixtures:[baseline,empty,sky]}]},
packageReview:FileRef, selectedEvidence:[FileRef], independentAudit:FileRef|null.

Manual source review object: kind='manual-seam-source-review', passed=true,
reviewed=true, reviewer, findings, candidateJarSha256, scanReportSha256,
sourceReviewRequiredObserved (exact original bool). It NEVER clears the flag.

Independent audit object: kind='independent-final-audit', passed=true,
independent=true, freshReview=true, reviewed=true, verifiedReportBindings=true,
reviewedWarnings=true, projectAuthor='Bing6512', reviewer, reviewerSessionId
(different from producer), reviewedAt (ISO timestamp), auditSubjectSha256,
jarSha256/sourceJarSha256/harnessSha256, plus nonempty checks. Obtain the subject
only after mandatory engineering evidence is ready. Subject excludes audit and
matrix file bytes to avoid a circular hash; it includes matrix core, actual pins,
validator bytes and project root. A test/engineering report cannot fill audit.

No games/builds; writes use exclusive creation. TEMP project_root injection exists
only for isolated selftests. Current real experiments are not auto-accepted.
"""
from __future__ import annotations

import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parent.parent
AUTHOR = "Bing6512"
ENVS = ("vanilla", "iris-native", "full-fsr")
PROFILES = dict(vanilla="vanilla", **{"iris-native": "iris", "full-fsr": "full"})
ALLOWED_REMOVED = {f"trans-removed-{x}:removed-world-restored" for x in ("none", "glass", "water")}
NEGATIVE_FLAGS = ("negativeIrisState", "negativeSeams", "negativeSkyCoverage", "negativeTransparency", "negativePrivateSkyClip", "negativeFixtureClock", "negativeCloudBuffer", "negativeEntityValidity", "negativeFixtureStartup", "negativeGlobalFluidFreeze")


class Invalid(ValueError):
    pass


def strict_object(pairs):
    value = {}
    for key, item in pairs:
        if key in value: raise Invalid("duplicate JSON key: " + key)
        value[key] = item
    return value


def reject_constant(token):
    raise Invalid("non-finite JSON value: "+token)


def hash_file(path):
    before = path.stat()
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024*1024), b""): digest.update(block)
    after = path.stat()
    if (before.st_size,before.st_mtime_ns)!=(after.st_size,after.st_mtime_ns): raise Invalid("input changed while hashing: " + str(path))
    return digest.hexdigest()


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value,sort_keys=True,separators=(",",":"),ensure_ascii=False,allow_nan=False).encode()).hexdigest()


class Gate:
    def __init__(self, root):
        self.root = root.resolve(strict=True)
        self.checks, self.warnings, self.report_paths = [], [], set()
        self.pins, self.fixtures, self.artifacts = {}, {}, {}
        self.waivers, self.experiments, self.seam_evidence = [], [], {}
        self.matrix_path = None
        self.validator_sha = hash_file(Path(__file__).resolve())

    def add(self, name, passed, **metrics):
        self.checks.append(dict(name=name,passed=bool(passed),**metrics))
        return bool(passed)

    def need(self, name, passed, **metrics):
        if not self.add(name,passed,**metrics): raise Invalid(name)

    def part(self, name, operation):
        try: return operation()
        except Invalid as error:
            self.add(name,False,error=str(error)); return None
        except (OSError,ValueError,KeyError,TypeError,IndexError,AttributeError,zipfile.BadZipFile) as error:
            self.add(name,False,error=str(error)); return None

    def path(self, value, directory=False):
        if not isinstance(value,str) or not value.strip(): raise ValueError("nonempty path string required")
        path = Path(value)
        path = (path if path.is_absolute() else self.root/path).resolve(strict=True)
        if not path.is_relative_to(self.root) or path==self.root: raise ValueError("path escapes project: " + value)
        if not (path.is_dir() if directory else path.is_file()): raise ValueError("wrong file/directory kind: " + value)
        return path

    def rel(self, path): return path.relative_to(self.root).as_posix()

    def pin(self, path):
        current = self.path(str(path))
        actual = hash_file(current)
        previous = self.pins.get(current)
        if previous is not None and previous!=actual: raise Invalid("pinned input changed: " + self.rel(current))
        self.pins[current] = actual
        return actual

    def read_json(self, path):
        before = self.pin(path)
        value = json.loads(path.read_text(encoding="utf-8-sig"),object_pairs_hook=strict_object,parse_constant=reject_constant)
        if self.pin(path)!=before: raise Invalid("JSON changed while reading")
        return value

    def file_ref(self, ref):
        path = self.path(ref["path"])
        wanted = ref["sha256"]
        self.need("valid-sha:"+self.rel(path),isinstance(wanted,str) and bool(re.fullmatch(r"[0-9a-fA-F]{64}",wanted)))
        actual = self.pin(path)
        self.need("pinned-sha:"+self.rel(path),actual==wanted.lower(),expected=wanted,actual=actual)
        return path,actual

    def report(self, ref):
        path,digest = self.file_ref(ref)
        value = self.read_json(path)
        if isinstance(value,list):
            self.need("single-explicit-report:"+self.rel(path),len(value)==1)
            value = value[0]
        self.need("report-object:"+self.rel(path),isinstance(value,dict))
        checks = value.get("checks")
        valid = isinstance(checks,list) and bool(checks) and all(isinstance(c,dict) and isinstance(c.get("name"),str)
                and type(c.get("passed")) is bool for c in checks)
        self.need("nonempty-report-checks:"+self.rel(path),valid)
        count = sum(c["passed"] for c in checks)
        self.need("honest-report-summary:"+self.rel(path),type(value.get("passed")) is bool
            and value["passed"]==all(c["passed"] for c in checks)
            and ("passedChecks" not in value or value["passedChecks"]==count)
            and ("totalChecks" not in value or value["totalChecks"]==len(checks)))
        self.report_paths.add(path)
        return value,path,digest

    def passed_report(self, ref):
        value,path,digest = self.report(ref)
        self.need("mandatory-report-passed:"+self.rel(path),value["passed"] is True)
        return value,path,digest

    def artifact(self, name, ref):
        path,digest = self.file_ref(ref)
        self.need(name+":jar-extension",path.suffix.lower()==".jar")
        with zipfile.ZipFile(path) as archive:
            if name=="sourceJar":
                self.need(name+":contains-source",any(n.endswith(".java") for n in archive.namelist()))
            else:
                metadata = json.loads(archive.read("fabric.mod.json"))
                self.need(name+":actual-mod-id",metadata["id"]==("skyboxblock" if name=="jar" else "skybox_ip_fixture"))
                self.need(name+":project-author",metadata.get("authors")==[AUTHOR],actual=metadata.get("authors"))
        self.artifacts[name] = dict(path=self.rel(path),sha256=digest)

    def fixture(self, key, spec):
        directory = self.path(spec["directory"],True)
        self.need(key+":unique-fixture-directory",all(f["path"]!=directory for f in self.fixtures.values()))
        manifest = self.read_json(directory/"fixture.json")
        run = self.read_json(directory/"run-result.json")
        self.pin(directory/"client.log")
        self.need(key+":real-run-completed",type(run.get("exitCode")) is int and run["exitCode"]==0
            and run.get("finished") is True and run.get("fatalErrors")==[] and run.get("renderThreadViolations")==[])
        errors = run.get("errors")
        self.need(key+":runtime-errors-preserved",isinstance(errors,list))
        categories = {name:0 for name in ("dataFixer","model","recipe","loot","ipOrChunkLoading","other")}
        for error in errors:
            text = str(error).lower()
            category = ("dataFixer" if "datafix" in text or "data fixer" in text else "model" if "model" in text else
                        "recipe" if "recipe" in text else "loot" if "loot" in text else
                        "ipOrChunkLoading" if "chunk" in text or "c2me" in text or "imm_ptl" in text else "other")
            categories[category] += 1
        if errors: self.warnings.append(dict(kind="runtime-ERROR-lines-retained",fixture=key,count=len(errors),categories=categories))
        mods = {}
        for path in sorted((directory/"mods").glob("*.jar")):
            with zipfile.ZipFile(path) as archive:
                identifier = json.loads(archive.read("fabric.mod.json"))["id"] if "fabric.mod.json" in archive.namelist() else path.name
            self.need(key+":unique-mod:"+identifier,identifier not in mods)
            mods[identifier] = self.pin(path)
        for field,identifier,artifact in (("mainMod","skyboxblock","jar"),("harnessMod","skybox_ip_fixture","harnessJar")):
            self.need(key+":"+field+":explicit-policy",spec[field] in ("candidate","absent"))
            expected = self.artifacts[artifact]["sha256"] if spec[field]=="candidate" else None
            self.need(key+":"+field+":actual-mods-sha",mods.get(identifier)==expected,expected=expected,actual=mods.get(identifier))
        if spec["mainMod"]=="candidate": self.need(key+":declared-jar-sha",manifest.get("jarSha256")==self.artifacts["jar"]["sha256"])
        elif "skyboxblockPresent" in manifest: self.need(key+":genuine-no-main-mod",manifest["skyboxblockPresent"] is False)
        if "harnessSha256" in manifest: self.need(key+":declared-harness-sha",manifest["harnessSha256"]==self.artifacts["harnessJar"]["sha256"])
        results = self.path(str(directory/"results"),True)
        metadata = sorted(results.rglob("*.json"))
        self.need(key+":actual-result-json",bool(metadata))
        for path in metadata: self.pin(path)
        shader_hash=None
        if manifest.get("shader"):
            shader_path=self.path(str(directory/"shaderpacks"/manifest["shader"]))
            self.need(key+":shader-inside-fixture",shader_path.is_relative_to(directory))
            shader_hash=self.pin(shader_path)
            if "shaderSha256" in manifest:self.need(key+":declared-shader-sha",manifest["shaderSha256"]==shader_hash)
        policy=manifest.get("distantGenerationPolicy",{})
        if isinstance(policy,dict) and policy.get("requested")=="disabled":
            config=self.path(str(directory/"config"/"DistantHorizons.toml"));self.pin(config)
            actual_dh=tomllib.loads(config.read_text(encoding="utf-8"))
            self.need(key+":explicit-controlled-distant-generation",actual_dh["common"]["worldGenerator"]["generatorPlan"]=="DISABLED"
                and actual_dh["client"]["advanced"]["debugging"]["rendererMode"]=="DEFAULT"
                and policy.get("actualGeneratorPlan")=="DISABLED" and policy.get("rendererModeUnchanged")=="DEFAULT"
                and "distanthorizons" in mods and isinstance(policy.get("limitation"),str) and bool(policy["limitation"].strip()))
            self.warnings.append(dict(kind="controlled-near-world-not-dynamic-DH-validation",fixture=key,
                note=policy["limitation"],actualConfigSha256=self.pins[config]))
        self.fixtures[key] = dict(path=directory,results=results,manifest=manifest,mods=mods,spec=spec,shaderHash=shader_hash,
            manifestSha256=self.pins[directory/"fixture.json"],errors=errors,errorCategories=categories,errorCount=len(errors),run=run)

    def positive(self, key, env=None, warmed_transparency=False):
        f = self.fixtures[key]
        self.need(key+":candidate-main",f["spec"]["mainMod"]=="candidate")
        self.need(key+":not-negative-or-native-control",not any(v is True for k,v in f["manifest"].items() if k.startswith("negative"))
            and f["manifest"].get("negativeFixtureClock") is False and f["manifest"].get("negativeCloudBuffer") is False
            and f["manifest"].get("negativeEntityValidity") is False and f["manifest"].get("negativeFixtureStartup") is False
            and f["manifest"].get("negativeGlobalFluidFreeze") is False
            and f["manifest"].get("nativeTransparencyControl") is not True)
        complete=f["manifest"].get("requestedScenes")==[]
        if warmed_transparency and not complete:
            warm=["align-solid-no-sky","align-clean"]
            measured=[f"trans-{slot}-{front}-{protection}-{state}" for slot in ("open","sky")
                for front in ("none","glass","water") for protection in ("none","pillar","hand","both")
                for state in ("empty","marker")]+["trans-removed-"+front for front in ("none","glass","water")]
            complete=f["manifest"].get("fixedHistoryWarmupScenes")==warm and f["manifest"].get("suite")=="all" \
                and f["manifest"].get("requestedScenes")==warm+measured
            complete=complete and {p.stem for p in f["results"].glob("trans-*.json")}==set(measured)
            for name in warm:
                meta=self.read_json(f["results"]/(name+".json"))
                self.need(key+":"+name+":warmup-actually-sky-free",meta.get("blocks")==meta.get("vertices")==0
                    and meta.get("itrpMaterialWrites") is False and meta.get("itrpBackgroundInjected") is False
                    and meta.get("finalResolved") is False)
            self.warnings.append(dict(kind="fixed-sky-free-history-preconditioning",fixture=key,warmupScenes=warm,
                measurementScenes=51,totalSceneProtocol=53,note="Complete original transparency measurements retained; not a partial full-model suite"))
        self.need(key+":complete-required-scene-protocol",complete,warmedTransparencyAllowed=warmed_transparency)
        if env is not None: self.need(key+":environment",f["manifest"].get("profile")==PROFILES[env])
        return f

    def bind(self, report, key, field="directory", suffix=""):
        f = self.fixtures[key]
        expected = f["results"]/suffix if suffix else f["results"]
        self.need(key+":report-fixture-binding:"+field,self.path(report[field],True)==expected)
        return f

    def core_report(self, ref, env, kind):
        f = self.positive(ref["fixture"],env)
        self.need(env+":"+kind+":complete-113-default-core",f["manifest"].get("suite")=="all" and f["run"].get("scenes")==113)
        value,path,_ = self.passed_report(ref)
        self.bind(value,ref["fixture"])
        names={c["name"] for c in value["checks"]}
        if kind=="world":
            valid=any(n.endswith(":final-display-matches-independent-sky") for n in names) and any(n.endswith(":sky-interior-complete") for n in names)
        elif kind=="time":
            valid={"all-real-dimension-scenes-present","nether:visible-day-night-difference","end:visible-day-night-difference"}<=names
        else:
            valid="all-nine-alignment-scenes-captured" in names and sum(":item-" in n and n.endswith("-at-correct-projected-location") for n in names)==16
            valid=valid and sum(n.endswith(":line-pixels-at-correct-projected-location") for n in names)==8
        self.need(env+":"+kind+":correct-report-role",valid)
        return value

    def transparency(self, spec, env):
        strict,path,digest = self.report(spec["strict"])
        key = spec["strict"]["fixture"]
        self.positive(key,env,warmed_transparency=True); self.bind(strict,key)
        fixture=self.fixtures[key]
        if fixture["manifest"].get("settledTransparency") is True:
            measured=[f"trans-{slot}-{front}-{protection}-{state}" for slot in ("open","sky") for front in ("none","glass","water") for protection in ("none","pillar","hand","both") for state in ("empty","marker")]+["trans-removed-"+front for front in ("none","glass","water")]
            warm=["warmup-empty-front","warmup-empty-oblique"]
            self.need(env+":complete-settled-53-scene-protocol",fixture["manifest"].get("suite")=="transparency"
                and fixture["manifest"].get("fixedHistoryWarmupScenes")==warm and fixture["run"].get("scenes")==53
                and {p.stem for p in fixture["results"].glob("*.json")}==set(warm+measured))
            self.need(env+":explicit-static-water-phase",fixture["manifest"].get("freezeWaterTextures") is True
                and fixture["manifest"].get("waterSpritePhase")=="first-declared-resource-frame")
            for name in warm:
                meta=self.read_json(fixture["results"]/(name+".json"))
                self.need(env+":"+name+":actually-sky-free",meta.get("blocks")==meta.get("vertices")==0
                    and meta.get("itrpMaterialWrites") is False and meta.get("itrpBackgroundInjected") is False and meta.get("finalResolved") is False)
            for name in measured:
                meta=self.read_json(fixture["results"]/(name+".json"));settle=meta.get("fixtureSettlement",{})
                self.need(env+":"+name+":actual-time-and-render-frame-settle",all(type(settle.get(field)) is int for field in
                    ("requiredMilliseconds","requiredFrames","observedMilliseconds","observedFrames"))
                    and settle.get("requiredMilliseconds")==15000 and settle.get("requiredFrames")==300
                    and settle.get("observedMilliseconds",0)>=15000 and settle.get("observedFrames",0)>=300
                    and settle.get("stablePoseRequired") is True and type(meta.get("fixtureInputGuardTicks")) is int
                    and meta.get("fixtureInputGuardTicks",0)>0)
                sprite_keys=("observedFixtureWaterSprites","frozenFixtureWaterSprites","suppressedFixtureWaterSpriteTicks","staticFixtureWaterSprites")
                valid_provided=all(type(meta[field]) is int and meta[field]>=0 for field in sprite_keys if field in meta)
                frozen_branch=type(meta.get("frozenFixtureWaterSprites")) is int and type(meta.get("suppressedFixtureWaterSpriteTicks")) is int \
                    and meta["frozenFixtureWaterSprites"]>0 and meta["suppressedFixtureWaterSpriteTicks"]>0
                static_branch=type(meta.get("staticFixtureWaterSprites")) is int and meta["staticFixtureWaterSprites"]>0
                self.need(env+":"+name+":actual-water-sprite-control",valid_provided and type(meta.get("observedFixtureWaterSprites")) is int
                    and meta["observedFixtureWaterSprites"]>0 and (frozen_branch or static_branch))
            self.warnings.append(dict(kind="fixed-static-water-and-settled-transparency-fixture",fixture=key,
                millisecondsPerMeasurement=15000,minimumRootFrames=300,waterSpritePhase="first-declared-resource-frame",
                note="Static fixture phase/history protocol, not a production animation freeze or arbitrary dynamic-fluid guarantee"))
        self.need(env+":current-structural-transparency-evidence",strict.get("markerMetricVersion")==2
            and sum(c["name"].endswith(":no-transmitted-marker") for c in strict["checks"])==12)
        if strict["passed"]:
            return
        failed = [c for c in strict["checks"] if not c["passed"]]
        self.need(env+":only-exact-removed-failures-calibratable",bool(failed)
            and len(failed)<=3 and len({c["name"] for c in failed})==len(failed)
            and all(c["name"] in ALLOWED_REMOVED for c in failed),strictFailures=failed)
        companion,cp,_ = self.passed_report(spec["calibrated"])
        self.need(env+":real-native-repeat-companion",companion.get("kind")=="native-repeat-calibrated-restoration"
            and companion.get("strictReportSha256")==digest)
        candidate = companion["candidate"]
        self.bind(candidate,key)
        subjects = [candidate]+companion["controls"]
        controls = [self.path(c["directory"],True) for c in companion["controls"]]
        self.need(env+":two-distinct-native-controls",len(controls)>=2 and len(set(controls))==len(controls))
        common = ("jarSha256","captureJitterBits","shaderArchiveSha256","inputSettingsSha256","freezeShaderTime")
        self.need(env+":same-candidate-time-phase-config",all(tuple(s[k] for k in common)==tuple(candidate[k] for k in common) for s in subjects)
            and candidate["jarSha256"]==self.artifacts["jar"]["sha256"] and candidate["freezeShaderTime"] is True)
        for subject in subjects:
            found = [f for f in self.fixtures.values() if f["results"]==self.path(subject["directory"],True)]
            self.need(env+":calibration-subject-fixture-listed",len(found)==1)
            f=found[0]
            self.need(env+":calibration-manifest-and-actual-mods",subject["manifestSha256"]==f["manifestSha256"]
                and subject["shaderArchiveSha256"]==f["shaderHash"]
                and f["mods"]==self.fixtures[key]["mods"] and f["manifest"].get("profile")==PROFILES[env])
            if subject is not candidate: self.need(env+":actual-never-sky-control",f["manifest"].get("nativeTransparencyControl") is True)
        waiver_check = next(c for c in companion["checks"] if c["name"]=="no-other-strict-failure-is-waived")
        self.need(env+":companion-binds-original-failures",set(waiver_check["originalFailedChecks"])=={c["name"] for c in failed}
            and companion["strictPassedChecks"]==strict["passedChecks"] and companion["strictTotalChecks"]==strict["totalChecks"])
        calibration_checks={c["name"]:c for c in companion["checks"]}
        for front in ("none","glass","water"):
            entry=calibration_checks[front+":candidate-restoration-within-native-budget"]
            limits,metrics=entry["budget"],entry["metrics"]
            self.need(env+":"+front+":native-budget-not-unbounded",limits==calibration_checks[front+":native-controls-stable-under-hard-caps"]["budget"]
                and limits["absoluteMaeCap"]<=1.5 and limits["absoluteP99Cap"]<=10
                and metrics["raw"]["mae"]<=min(limits["mae"],limits["absoluteMaeCap"])
                and metrics["raw"]["p99"]<=min(limits["p99"],limits["absoluteP99Cap"])
                and metrics["lowpass"]["mae"]<=limits["lowpassMae"]<=1
                and metrics["lowpass"]["p99"]<=limits["lowpassP99"]<=2
                and metrics["lowpass"]["maximum"]<=limits["lowpassMaximum"]<=4)
        self.need(env+":calibration-reason-preserved",isinstance(companion.get("note"),str) and bool(companion["note"].strip()))
        self.waivers.append(dict(environment=env,strictReport=self.rel(path),strictReportSha256=digest,
            strictPassed=False,strictPassedChecks=strict["passedChecks"],strictTotalChecks=strict["totalChecks"],
            originalFailedChecks=failed,companion=self.rel(cp),reason=companion["note"],calibrationChecks=companion["checks"]))
        self.warnings.append(dict(kind="strict-restoration-failures-calibrated-not-erased",environment=env,failedChecks=failed))

    def seams(self, spec):
        report,path,digest = self.passed_report(spec["report"])
        self.positive(spec["positive"])
        self.bind(report,spec["positive"],suffix="seamscan")
        negative=report["negativeControl"]
        self.bind(negative,spec["negative"],suffix="seamscan")
        self.need("seams:explicit-real-pair",report.get("negativeSeams") is False and negative.get("negativeSeams") is True
            and report.get("negativeControlValid") is True
            and self.fixtures[spec["negative"]]["manifest"].get("negativeSeams") is True)
        for label in ("positive","negative"):
            f=self.fixtures[spec[label]]
            summary=self.read_json(f["results"]/"seamscan"/"seamscan-summary.json")
            stages=summary["stages"]
            self.need("seams:"+label+":phase-complete",summary["finished"] is True and summary["phaseComplete"] is True
                and summary["plannedStages"]==summary["completedStages"]==len(stages) and len(stages)>=64
                and summary["negativeSeams"]==(label=="negative")
                and all(s["complete"] is True and s["jitterCount"]>=23 and len({j["key"] for j in s["jitterStates"]})>=23 for s in stages))
        manual,mp,_=self.passed_report(spec["sourceReview"])
        self.need("seams:manual-source-review-binds-actual-pair",manual.get("kind")=="manual-seam-source-review"
            and manual.get("reviewed") is True and all(isinstance(manual.get(k),str) and bool(manual[k].strip()) for k in ("reviewer","findings"))
            and manual.get("candidateJarSha256")==self.artifacts["jar"]["sha256"] and manual.get("scanReportSha256")==digest
            and type(report.get("sourceReviewRequired")) is bool
            and manual.get("sourceReviewRequiredObserved") is report["sourceReviewRequired"])
        self.seam_evidence=dict(report=self.rel(path),sourceReviewRequired=report["sourceReviewRequired"],
            manualSourceReview=self.rel(mp),manualReviewed=True,negativeVisualReproductionFound=report.get("negativeVisualReproductionFound"),
            negativeReportPassed=negative["passed"],manualFindings=manual["findings"])
        if report["sourceReviewRequired"]: self.warnings.append(dict(kind="seam-source-review-flag-retained",**self.seam_evidence))

    def positions(self, spec, env):
        self.positive(spec["fixture"],env)
        report,path,_=self.passed_report(spec["report"])
        self.bind(report,spec["fixture"],"source")
        self.need(env+":derived-position-controls",report.get("syntheticDerivedImageControls") is True)
        wanted={"line-shift-20-raster-px":8,"item-image-shift-60-display-px":16}
        entries={c["name"]:c for c in report["checks"]}
        self.need(env+":both-exact-position-negative-controls",set(entries)==set(wanted))
        derived=self.path(spec["derivedDirectory"],True)
        source=self.fixtures[spec["fixture"]]["results"]
        original=list(source.glob("align-*.json")); self.need(env+":position-source-metadata",bool(original))
        for name,count in wanted.items():
            c=entries[name]
            self.need(env+":"+name+":failure-detected-not-waived",c["expectedPositionFailures"]==c["actualPositionFailures"]==count
                and c.get("metadataByteIdentical") is True and c.get("allLogicalStateChecksStillPass") is True)
            for meta in original:
                target=self.path(str(derived/name/meta.name))
                self.need(env+":"+name+":actual-metadata-bytes:"+meta.name,self.pin(meta)==self.pin(target))
                self.report_paths.add(target)  # Include actual derived JSON proof in the archive.

    def portal_case(self, spec, label, supported):
        report,path,_=self.report(spec["report"])
        keys=spec["fixtures"]
        self.need(label+":three-real-portal-variants",isinstance(keys,list) and len(keys)==3 and len(set(keys))==3
            and [self.fixtures[k]["manifest"].get("variant") for k in keys]==["baseline","empty","sky"])
        for index,key in enumerate(keys):
            f=self.fixtures[key]
            self.need(label+":"+key+":portal-artifact-policy",f["spec"]["harnessMod"]=="candidate"
                and f["spec"]["mainMod"]==("absent" if index==0 else "candidate"))
        if supported:
            self.need(label+":basic-no-shader-portal-passed",report["passed"] is True and report.get("baseCombinationPassed") is True
                and all(self.fixtures[k]["manifest"].get("shader") is None for k in keys))
        else:
            actual_pass=report["passed"]
            faithful=(spec.get("supported") is True and spec.get("status") in {"limited-basic","supported-basic"}) if actual_pass else (
                spec.get("supported") is False and spec.get("status")=="unsupported")
            self.need(label+":experiment-status-matches-real-result",faithful
                and isinstance(spec.get("reason"),str) and bool(spec["reason"].strip()))
            value=dict(environment=label,supported=actual_pass,status=spec["status"],rawReportPassed=actual_pass,
                       untested=["rotation","scaling","nesting","cross-dimension"],
                       originalFailedChecks=[c for c in report["checks"] if not c["passed"]],reason=spec["reason"],report=self.rel(path))
            self.experiments.append(value)
            self.warnings.append(dict(kind="limited-basic-Iris-portal-only" if actual_pass else "unsupported-Iris-portal-experiment",**value))
        self.warnings.append(dict(kind="aggregate-report-binding-needs-independent-review",report=self.rel(path),fixtureIds=keys))

    def package(self, ref):
        report,path,_=self.passed_report(ref)
        names={c["name"] for c in report["checks"]}
        self.need("package:real-package-report-role",{"mod-id-and-version","production-has-no-world-color-readback","real-refmap-is-packaged","source-distribution-exists"}<=names)
        expected=self.artifacts["jar"]["sha256"]
        self.need("package:actual-final-jar-reviewed",report.get("sha256")==expected and self.pin(self.path(report["jar"]))==expected)
        source=next(c for c in report["checks"] if c["name"]=="source-distribution-exists")
        self.need("package:actual-source-distribution-reviewed",self.pin(self.path(source["path"]))==self.artifacts["sourceJar"]["sha256"])

    def subject(self, matrix):
        core={k:v for k,v in matrix.items() if k!="independentAudit"}
        return canonical_hash(dict(project=str(self.root),projectAuthor=AUTHOR,
            validatorSha256=self.validator_sha,matrix=core,
            actualPins={self.rel(p):h for p,h in sorted(self.pins.items()) if p!=self.matrix_path}))

    def audit(self, ref, matrix, subject):
        report,path,_=self.passed_report(ref)
        self.need("audit:independent-fresh-review-not-engineering-selftest",report.get("kind")=="independent-final-audit"
            and all(report.get(k) is True for k in ("independent","freshReview","reviewed","verifiedReportBindings","reviewedWarnings"))
            and all(isinstance(report.get(k),str) and bool(report[k].strip()) for k in ("reviewer","reviewerSessionId","reviewedAt"))
            and report["reviewerSessionId"]!=matrix["producerSessionId"] and report.get("projectAuthor")==AUTHOR)
        reviewed=datetime.fromisoformat(report["reviewedAt"].replace("Z","+00:00"))
        self.need("audit:dated-review",reviewed.tzinfo is not None)
        self.need("audit:exact-current-subject-and-artifacts",report.get("auditSubjectSha256")==subject
            and all(report.get(field)==self.artifacts[artifact]["sha256"] for field,artifact in
                    (("jarSha256","jar"),("sourceJarSha256","sourceJar"),("harnessSha256","harnessJar"))))


def verify(matrix_path, *, project_root=None, prepare_audit=False):
    gate=Gate(ROOT if project_root is None else Path(project_root))
    try:
        gate.matrix_path=gate.path(str(matrix_path))
        matrix=gate.read_json(gate.matrix_path)
    except (OSError,ValueError,TypeError) as error:
        return dict(kind="test5-engineering-acceptance",passed=False,checks=[dict(name="readable-explicit-matrix",passed=False,error=str(error))],
                    passedChecks=0,totalChecks=1,warnings=[])
    allowed={"schemaVersion","projectAuthor","releaseId","producerSessionId","artifacts","fixtures","environments",
             "seams","positionControls","portal","packageReview","independentAudit","selectedEvidence","additionalReports","notes"}
    valid=isinstance(matrix,dict) and set(matrix)<=allowed and type(matrix.get("schemaVersion")) is int and matrix["schemaVersion"]==1
    valid=valid and matrix.get("projectAuthor")==AUTHOR and all(isinstance(matrix.get(k),str) and bool(matrix[k].strip()) for k in ("releaseId","producerSessionId"))
    valid=valid and all(isinstance(matrix.get(k),dict) for k in ("artifacts","fixtures","environments","seams","positionControls","portal","packageReview"))
    valid=valid and isinstance(matrix.get("selectedEvidence"),list) and isinstance(matrix.get("portal",{}).get("experiments"),list)
    valid=valid and set(matrix["portal"])=={"noShader","experiments"} and isinstance(matrix.get("additionalReports",[]),list)
    if not gate.add("matrix:explicit-test5-schema",valid):
        return dict(kind="test5-engineering-acceptance",passed=False,readyForIndependentAudit=False,checks=gate.checks,
                    passedChecks=0,totalChecks=len(gate.checks),warnings=[])
    for name in ("jar","sourceJar","harnessJar"):
        gate.part("artifact:"+name,lambda name=name:gate.artifact(name,matrix["artifacts"][name]))
    gate.part("artifact-distinction",lambda:gate.need("artifacts:distinct-paths",len({v["path"] for v in gate.artifacts.values()})==3))
    for key,spec in matrix.get("fixtures",{}).items(): gate.part("fixture:"+key,lambda key=key,spec=spec:gate.fixture(key,spec))
    gate.part("environment-set",lambda:gate.need("environments:all-three",set(matrix["environments"])==set(ENVS)))
    for env in ENVS:
        for role in ("world","time","alignment"):
            gate.part(env+":"+role,lambda env=env,role=role:gate.core_report(matrix["environments"][env][role],env,role))
        gate.part(env+":transparency",lambda env=env:gate.transparency(matrix["environments"][env]["transparency"],env))
        gate.part(env+":position-controls",lambda env=env:gate.positions(matrix["positionControls"][env],env))
    gate.part("seams:paired-and-reviewed",lambda:gate.seams(matrix["seams"]))
    gate.part("portal:no-shader",lambda:gate.portal_case(matrix["portal"]["noShader"],"no-shader",True))
    experiments=matrix.get("portal",{}).get("experiments",[])
    gate.part("portal:experiment-set",lambda:gate.need("portal:two-explicit-Iris-experiment-records",len(experiments)==2
        and {e.get("environment") for e in experiments}=={"iris-native","iris-fsr"}))
    for exp in experiments: gate.part("portal:experiment",lambda exp=exp:gate.portal_case(exp,exp["environment"],False))
    gate.part("package-review",lambda:gate.package(matrix["packageReview"]))
    for ref in matrix.get("additionalReports",[]):
        gate.part("additional-mandatory-report",lambda ref=ref:gate.passed_report(ref))
    evidence=[]
    for ref in matrix.get("selectedEvidence",[]):
        item=gate.part("selected-evidence",lambda ref=ref:gate.file_ref(ref))
        if item:evidence.append(gate.rel(item[0]))
    gate.part("selected-evidence-present",lambda:gate.need("evidence:curated-selection-present",bool(evidence)))
    subject=gate.subject(matrix)
    ready=all(c["passed"] for c in gate.checks)
    if not prepare_audit: gate.part("independent-final-audit",lambda:gate.audit(matrix["independentAudit"],matrix,subject))
    gate.part("input-stability",lambda:gate.need("all-pinned-inputs-still-identical",hash_file(Path(__file__).resolve())==gate.validator_sha
        and all(gate.path(str(p))==p and hash_file(p)==h for p,h in gate.pins.items())))
    ready=ready and all(c["passed"] for c in gate.checks if c["name"] in {"input-stability","all-pinned-inputs-still-identical"})
    passed=not prepare_audit and bool(gate.checks) and all(c["passed"] for c in gate.checks)
    result=dict(kind="test5-engineering-acceptance",schemaVersion=1,projectAuthor=AUTHOR,releaseId=matrix.get("releaseId"),
        passed=passed,readyForIndependentAudit=ready,auditSubjectSha256=subject,
        fixtures=[dict(directory=gate.rel(f["path"]),id=k,errorCount=f["errorCount"],errors=f["errors"],
                       errorCategories=f["errorCategories"],errorCategoriesAreHeuristic=True,modHashes=f["mods"])
                  for k,f in gate.fixtures.items()],reports=sorted({gate.rel(p) for p in gate.report_paths}|{gate.rel(gate.matrix_path)}),
        selectedEvidence=evidence,checks=gate.checks,passedChecks=sum(c["passed"] for c in gate.checks),totalChecks=len(gate.checks),
        strictTransparencyCalibrations=gate.waivers,seams=gate.seam_evidence,
        supportBoundaries=dict(noShaderPortal=any(c["name"]=="no-shader:basic-no-shader-portal-passed" and c["passed"] for c in gate.checks),
                               irisPortalSupported=False,portalExperiments=gate.experiments,
                               unsupportedPortalExperiments=[e for e in gate.experiments if not e["supported"]]),warnings=gate.warnings,
        inputPins={gate.rel(p):h for p,h in sorted(gate.pins.items())},
        note="Engineering binding/policy gate only. Requires a separate fresh independent audit; raw strict failures/review flags/nonfatal ERRORs remain visible.")
    for field,hash_field in (("jar","jarSha256"),("sourceJar","sourceJarSha256"),("harnessJar","harnessSha256")):
        result[field]=gate.artifacts.get(field,{}).get("path");result[hash_field]=gate.artifacts.get(field,{}).get("sha256")
    return result


def example():
    def ref(path,fixture=None): return dict(path=path,sha256="<64-hex-actual-file-sha256>",**({"fixture":fixture} if fixture else {}))
    fixtures={}
    def fixture(key,main="candidate",harness="absent"):
        fixtures[key]=dict(directory="build/render-regression/FINAL-"+key,mainMod=main,harnessMod=harness);return key
    environments={};positions={}
    for env in ENVS:
        key=fixture(env)
        environments[env]={role:ref(f"build/reports/FINAL-{env}-{role}.json",key) for role in ("world","time","alignment")}
        trans=fixture("trans-"+env)
        environments[env]["transparency"]=dict(strict=ref(f"build/reports/FINAL-{env}-trans-strict.json",trans))
        positions[env]=dict(report=ref(f"build/reports/FINAL-{env}-position-controls.json"),fixture=key,
                            derivedDirectory=f"build/position-oracle-controls/FINAL-{env}")
    pos,neg=fixture("scan-positive"),fixture("scan-negative")
    portal={};experiments=[]
    for env in ("no-shader","iris-native","iris-fsr"):
        ids=[fixture("portal-"+env+"-"+variant,"absent" if variant=="baseline" else "candidate","candidate") for variant in ("baseline","empty","sky")]
        item=dict(report=ref("build/reports/FINAL-portal-"+env+".json"),fixtures=ids)
        if env=="no-shader":portal["noShader"]=item
        else:experiments.append(dict(environment=env,status="unsupported",supported=False,reason="Explicit tested boundary, retain original failure details",**item))
    portal["experiments"]=experiments
    return dict(schemaVersion=1,projectAuthor=AUTHOR,releaseId="test.5-FINAL",producerSessionId="session-PRODUCER",
        artifacts={k:ref("build/libs/FINAL-"+k+".jar") for k in ("jar","sourceJar","harnessJar")},fixtures=fixtures,
        environments=environments,seams=dict(positive=pos,negative=neg,report=ref("build/reports/FINAL-scan-pair.json"),
            sourceReview=ref("build/reports/FINAL-manual-source-review.json")),positionControls=positions,portal=portal,
        packageReview=ref("build/reports/FINAL-package.json"),selectedEvidence=[ref("docs/screenshots/FINAL-selected.png")],
        independentAudit=None,
        notes="TEMPLATE ONLY, not acceptance. For strict removed failures add transparency.calibrated FileRef and list both never-sky control fixtures; then obtain subject digest and independent audit.")


def write_exclusive(output, result):
    path=Path(output)
    if not path.is_absolute():path=ROOT/path
    path=path.resolve()
    if not path.is_relative_to(ROOT.resolve()):raise ValueError("output must be inside project")
    path.parent.mkdir(parents=True,exist_ok=True)
    with path.open("x",encoding="utf-8") as stream:json.dump(result,stream,indent=2,ensure_ascii=False,allow_nan=False);stream.write("\n")


def main():
    parser=argparse.ArgumentParser(description=__doc__,formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("matrix",nargs="?",type=Path)
    parser.add_argument("--output",type=Path)
    parser.add_argument("--subject-digest",action="store_true")
    parser.add_argument("--example",action="store_true")
    args=parser.parse_args()
    if args.example:print(json.dumps(example(),indent=2,ensure_ascii=False));return 0
    if args.matrix is None:parser.error("explicit matrix required")
    if not args.subject_digest and args.output is None:parser.error("--output is required for acceptance (exclusive create)")
    result=verify(args.matrix,prepare_audit=args.subject_digest)
    if args.subject_digest:
        print(json.dumps({k:result.get(k) for k in ("auditSubjectSha256","readyForIndependentAudit")},indent=2))
        return 0 if result.get("readyForIndependentAudit") else 1
    write_exclusive(args.output,result)
    print(f"{result['passedChecks']}/{result['totalChecks']} {'PASS' if result['passed'] else 'FAIL'}")
    for c in result["checks"]:
        if not c["passed"]:print("FAIL",json.dumps(c,ensure_ascii=False))
    return 0 if result["passed"] else 1


if __name__=="__main__":raise SystemExit(main())
