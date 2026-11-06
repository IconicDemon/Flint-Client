# Dev only: samples one game's process while a benchmark runs. Waits for the Java process whose command line contains
# MARKER (the run's folder), then every INTERVAL ms writes: time, CPU time used (all cores, ms), working set and private
# memory (MB), and the GPU's 3D engine utilisation for that process (Windows' GPU Engine counters, percent; empty when
# the counters aren't available). Stops when the process exits.
param([Parameter(Mandatory)][string]$Marker, [Parameter(Mandatory)][string]$Out, [int]$IntervalMs = 2000)

$deadline = (Get-Date).AddMinutes(5)
$proc = $null
while (-not $proc -and (Get-Date) -lt $deadline) {
	$proc = Get-CimInstance Win32_Process -Filter "Name='java.exe' OR Name='javaw.exe'" | Where-Object { $_.CommandLine -like "*$Marker*" } | Select-Object -First 1
	if (-not $proc) { Start-Sleep -Milliseconds 500 }
}
if (-not $proc) { exit 1 }
$id = $proc.ProcessId
"t_ms,cpu_ms,ws_mb,private_mb,gpu3d" | Out-File $Out -Encoding utf8
$start = Get-Date
while ($true) {
	$p = Get-Process -Id $id -ErrorAction SilentlyContinue
	if (-not $p) { break }
	$gpu = ""
	try {
		$samples = (Get-Counter "\GPU Engine(pid_${id}_*engtype_3D)\Utilization Percentage" -ErrorAction Stop).CounterSamples
		$gpu = [math]::Round(($samples | Measure-Object CookedValue -Sum).Sum, 1)
	} catch {}
	$t = [int]((Get-Date) - $start).TotalMilliseconds
	"$t,$([int64]$p.TotalProcessorTime.TotalMilliseconds),$([int]($p.WorkingSet64 / 1MB)),$([int]($p.PrivateMemorySize64 / 1MB)),$gpu" | Out-File $Out -Append -Encoding utf8
	Start-Sleep -Milliseconds $IntervalMs
}
