#!/usr/bin/env pwsh
# -----------------------------------------------------------------------------
# verify-remap.ps1 -- pre-deploy hard check: can this jar actually run on Fabric?
#
# BACKGROUND (2026-11-14 incident, skyboxblock 1.0.8-test.2-html-1.21.1):
#   A jar was assembled manually with javac + tiny-remapper because Loom could not
#   run under the then-active sandbox ACL. The manual pipeline did not remap the
#   Minecraft class references, so at runtime the game died with:
#       NoClassDefFoundError: net/minecraft/world/level/ItemLike
#   That jar's SHA256 (D51559F6...) was identical to build/libs, proving it was a
#   raw build artifact copied straight into mods.
#   This script exists to make that class of mistake impossible to miss.
#
# USAGE
#   .\tools\verify-remap.ps1 -Jar ..\build\libs\skyboxblock-1.0.8-test.2-html-1.21.1.jar
#   .\tools\verify-remap.ps1 -Jar FILE -Target 1.21.1
#   .\tools\verify-remap.ps1 -Jar FILE -Target 26.2
#
# RULES
#   Target 1.20.x or lower / 1.21.x : named refs MUST be 0, intermediary MUST be > 0,
#                                     and the mixin refmap MUST map to intermediary.
#   Target 26.x and later           : Minecraft is no longer obfuscated (26.1+),
#                                     so named refs are CORRECT and no remap happens.
#   Target omitted                  : inferred from fabric.mod.json depends.minecraft.
#
# EXIT CODES
#   0 = jar passes the rules for its target
#   1 = jar must not be deployed
#
# NOTE: keep this file pure ASCII. PowerShell 5.1 parses BOM-less .ps1 as GBK and
#       non-ASCII literals break the parser (this repo has been bitten repeatedly).
# -----------------------------------------------------------------------------

param(
    [Parameter(Mandatory = $true)]
    [string]$Jar,
    [string]$Target = ''
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem -ErrorAction SilentlyContinue

if (-not (Test-Path -LiteralPath $Jar)) {
    Write-Host "FAIL: jar not found: $Jar" -ForegroundColor Red
    exit 1
}

$jarPath = (Resolve-Path -LiteralPath $Jar).Path
$file = Get-Item -LiteralPath $jarPath
Write-Host "jar    : $($file.FullName)"
Write-Host "size   : $([math]::Round($file.Length / 1MB, 2)) MB"
Write-Host "mtime  : $($file.LastWriteTime.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host ('-' * 78)

$zip = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
try {
    $entries = @($zip.Entries)
    Write-Host "entries: $($entries.Count)"

    # ---- fabric.mod.json ----
    $mj = $entries | Where-Object { $_.FullName -eq 'fabric.mod.json' } | Select-Object -First 1
    if (-not $mj) {
        Write-Host "FAIL: fabric.mod.json missing -> not a Fabric mod jar" -ForegroundColor Red
        exit 1
    }
    $s = $mj.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
    $modJsonText = [System.Text.Encoding]::UTF8.GetString($ms.ToArray()); $ms.Close()
    $modJson = $modJsonText | ConvertFrom-Json

    Write-Host "mod id : $($modJson.id)"
    Write-Host "version: $($modJson.version)"

    # ---- infer target ----
    $depMc = ''
    if ($modJson.depends -and $modJson.depends.minecraft) {
        $depMc = [string]$modJson.depends.minecraft
    }
    if (-not $Target) {
        if ($depMc -match '(\d+)\.(\d+)') {
            $Target = "$($matches[1]).$($matches[2])"
        }
    }
    Write-Host "depends.minecraft: $depMc"
    Write-Host "target : $Target"
    Write-Host ('-' * 78)

    # ---- locate entrypoint class ----
    $mainEntryName = $null
    if ($modJson.entrypoints -and $modJson.entrypoints.main) {
        $mainClass = [string]($modJson.entrypoints.main | Select-Object -First 1)
        $mainEntryName = ($mainClass -replace '\.', '/') + '.class'
    }
    if (-not $mainEntryName) {
        $mainEntryName = $entries |
            Where-Object { $_.FullName -match '\.class$' -and $_.FullName -match '(?i)Mod\.class$' } |
            Select-Object -First 1 -ExpandProperty FullName
    }
    if (-not $mainEntryName) {
        Write-Host "FAIL: cannot locate main entrypoint class" -ForegroundColor Red
        exit 1
    }

    $ent = $entries | Where-Object { $_.FullName -eq $mainEntryName } | Select-Object -First 1
    if (-not $ent) {
        Write-Host "FAIL: entrypoint class not in jar: $mainEntryName" -ForegroundColor Red
        exit 1
    }
    $s = $ent.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
    $bytes = $ms.ToArray(); $ms.Close()
    $ascii = [System.Text.Encoding]::GetEncoding('ISO-8859-1').GetString($bytes)

    $refs = [regex]::Matches($ascii, 'net/minecraft/[a-zA-Z0-9_/\$]{2,60}') |
        ForEach-Object { $_.Value } | Sort-Object -Unique
    $named = @($refs | Where-Object { $_ -notmatch 'class_\d+' })
    $inter = @($refs | Where-Object { $_ -match 'class_\d+' })

    Write-Host "entrypoint: $mainEntryName"
    Write-Host "  named (mojang) refs : $($named.Count)"
    Write-Host "  intermediary refs   : $($inter.Count)"
    if ($named.Count -gt 0) {
        $named | Select-Object -First 5 | ForEach-Object { Write-Host "    named -> $_" }
    }

    # ---- refmap ----
    $mixinsJson = $entries | Where-Object { $_.FullName -match '(?i)mixins.*\.json$' -and $_.FullName -notmatch 'refmap' } | Select-Object -First 1
    $refmapName = $null
    if ($mixinsJson) {
        $s = $mixinsJson.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
        $mx = [System.Text.Encoding]::UTF8.GetString($ms.ToArray()); $ms.Close()
        $m = [regex]::Match($mx, '"refmap"\s*:\s*"([^"]*)"')
        if ($m.Success) { $refmapName = $m.Groups[1].Value }
        Write-Host "mixins config      : $($mixinsJson.FullName)"
        Write-Host "declared refmap    : $(if ($refmapName) { $refmapName } else { '(none)' })"
    }

    $refmapOk = $false
    if ($refmapName) {
        $rf = $entries | Where-Object { $_.FullName -eq $refmapName } | Select-Object -First 1
        if ($rf) {
            $s = $rf.Open(); $ms = New-Object System.IO.MemoryStream; $s.CopyTo($ms); $s.Close()
            $rfText = [System.Text.Encoding]::UTF8.GetString($ms.ToArray()); $ms.Close()
            try {
                $rfj = $rfText | ConvertFrom-Json
                $rfClasses = @($rfj.mappings.PSObject.Properties).Count
                # Only inspect the JSON VALUE side. Keys are allowed to be named-space
                # injection selectors (Loom emits them that way for fully-qualified
                # @Inject targets); that is the correct form.
                $valText = ($rfj.mappings.PSObject.Properties | ForEach-Object { $_.Value | ConvertTo-Json -Compress -Depth 8 }) -join ''
                $valOnly = ([regex]::Matches($valText, '":"([^"]*)"') | ForEach-Object { $_.Groups[1].Value }) -join ' '
                $valInter = ([regex]::Matches($valOnly, 'class_\d+')).Count
                $valNamed = ([regex]::Matches($valOnly, 'net/minecraft/(?!class_\d)[a-z]')).Count
                Write-Host "refmap entries     : $rfClasses mixin classes"
                Write-Host "  intermediary in values : $valInter"
                Write-Host "  named in values        : $valNamed"
                $refmapOk = ($rfClasses -gt 0 -and $valInter -gt 0 -and $valNamed -eq 0)
            }
            catch {
                Write-Host "refmap parse error : $($_.Exception.Message)" -ForegroundColor Yellow
                $refmapOk = $false
            }
        }
        else {
            Write-Host "refmap file missing: $refmapName" -ForegroundColor Yellow
        }
    }

    # ---- verdict ----
    Write-Host ('-' * 78)
    $isModern = ($Target -and $Target -match '^2[6-9]\.')
    if ($isModern) {
        Write-Host "target $Target is 26.x: Minecraft is unobfuscated, no remap required" -ForegroundColor Cyan
        Write-Host "PASS: named refs are expected for 26.x" -ForegroundColor Green
        exit 0
    }

    $problems = @()
    if ($named.Count -gt 0) { $problems += "main class still has $($named.Count) named (mojang) refs" }
    if ($inter.Count -eq 0) { $problems += "main class has no intermediary refs" }
    if (-not $refmapOk) { $problems += "refmap missing or not mapped to intermediary" }

    if ($problems.Count -eq 0) {
        Write-Host "PASS: properly remapped, safe for Fabric production" -ForegroundColor Green
        exit 0
    }

    Write-Host "FAIL: do not deploy this jar" -ForegroundColor Red
    $problems | ForEach-Object { Write-Host "  - $_" -ForegroundColor Red }
    Write-Host ""
    Write-Host "Fix: rebuild with gradlew remapJar. Do NOT hand-assemble with javac + tiny-remapper."
    Write-Host "  cd into the project, then:"
    Write-Host "  .\gradlew.bat remapJar --no-daemon --console=plain"
    Write-Host "A correct build produces BOTH build/libs/NAME.jar and build/devlibs/NAME-dev.jar."
    exit 1
}
finally {
    $zip.Dispose()
}
