# Copyright (c) 2026 Weasis Team and other contributors.
# SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
#
# Reads and writes the ProScan database through the Jet engine of Windows, which honors the record
# locks of ProScan. Runs in the 32-bit PowerShell: Jet exists only in 32 bits. The arguments come
# in environment variables, Base64 of UTF-8; each output line is a list of Base64 fields, '-' for
# a missing value.

$ErrorActionPreference = 'Stop'

function Get-Arg([string]$name) {
  $value = [Environment]::GetEnvironmentVariable($name)
  if ([string]::IsNullOrEmpty($value)) { return $null }
  return [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($value))
}

function Write-Line([object[]]$fields) {
  $encoded = foreach ($f in $fields) {
    if ($null -eq $f -or $f -is [DBNull]) { '-' }
    elseif ($f -is [DateTime]) {
      $text = $f.ToString('yyyy-MM-dd HH:mm:ss', [Globalization.CultureInfo]::InvariantCulture)
      [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($text))
    }
    else { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$f)) }
  }
  [Console]::Out.WriteLine(($encoded -join ' '))
}

function Add-Param($command, [System.Data.OleDb.OleDbType]$type, $value) {
  $p = $command.Parameters.Add('p' + $command.Parameters.Count, $type)
  $p.Value = $value
}

function As-Text($value) {
  if ($null -eq $value -or $value -is [DBNull]) { return '' }
  return [string]$value
}

function As-Int($value) {
  if ($null -eq $value -or $value -is [DBNull] -or [string]$value -eq '') { return 0 }
  return [int]$value
}

function Write-Rows($connection, [string]$tag, [string]$sql) {
  $command = $connection.CreateCommand()
  $command.CommandText = $sql
  $reader = $command.ExecuteReader()
  try {
    while ($reader.Read()) {
      $row = @($tag)
      for ($i = 0; $i -lt $reader.FieldCount; $i++) { $row += , $reader.GetValue($i) }
      Write-Line $row
    }
  } finally { $reader.Close() }
}

try {
  # Shared mode: ProScan and the other computers keep working on the database meanwhile
  $source = 'Provider=Microsoft.Jet.OLEDB.4.0;Mode=Share Deny None;Data Source="' +
    (Get-Arg 'PROSCAN_DB') + '"'
  $connection = New-Object System.Data.OleDb.OleDbConnection -ArgumentList $source
  $connection.Open()
  try {
    switch (Get-Arg 'PROSCAN_MODE') {
      'read' {
        $command = $connection.CreateCommand()
        $command.CommandText =
          'SELECT ID, Description, DoctorID, DiagnID FROM FluorTab WHERE FileName = ?'
        Add-Param $command VarWChar (Get-Arg 'PROSCAN_FILE')
        $reader = $command.ExecuteReader()
        try {
          while ($reader.Read()) {
            Write-Line @('R', $reader.GetValue(0), $reader.GetValue(1), $reader.GetValue(2),
              $reader.GetValue(3))
          }
        } finally { $reader.Close() }
      }
      'check' {
        $command = $connection.CreateCommand()
        $command.CommandText = 'SELECT COUNT(*) FROM FluorTab'
        Write-Line @('K', $command.ExecuteScalar())
      }
      'catalog' {
        Write-Rows $connection 'D' 'SELECT ID, FIO FROM DoctorsSPR ORDER BY FIO'
        Write-Rows $connection 'G' 'SELECT ID, DiagnosName FROM DiagnosesSPR ORDER BY DiagnosName'
      }
      'browse' {
        # Jet needs the parentheses around each join
        $limit = 3000
        if (Get-Arg 'PROSCAN_LIMIT') { $limit = [int](Get-Arg 'PROSCAN_LIMIT') }
        $sql = 'SELECT TOP ' + $limit + ' f.ID, f.[Date], f.[Time], p.LastName, p.FirstName, ' +
          'p.MidleName, p.DOB, f.FileName, d.FIO, g.DiagnosName, f.Description ' +
          'FROM ((FluorTab AS f LEFT JOIN Patients AS p ON f.PatientID = p.ID) ' +
          'LEFT JOIN DoctorsSPR AS d ON f.DoctorID = d.ID) ' +
          'LEFT JOIN DiagnosesSPR AS g ON f.DiagnID = g.ID ' +
          'WHERE f.[Date] >= ? AND f.[Date] < ?'
        $name = Get-Arg 'PROSCAN_NAME'
        if ($name) { $sql += ' AND p.LastName LIKE ?' }
        $command = $connection.CreateCommand()
        $command.CommandText = $sql + ' ORDER BY f.[Date] DESC, f.[Time] DESC'
        $culture = [Globalization.CultureInfo]::InvariantCulture
        Add-Param $command Date ([datetime]::ParseExact((Get-Arg 'PROSCAN_FROM'), 'yyyy-MM-dd', $culture))
        Add-Param $command Date ([datetime]::ParseExact((Get-Arg 'PROSCAN_TO'), 'yyyy-MM-dd', $culture))
        if ($name) { Add-Param $command VarWChar ($name + '%') }
        $reader = $command.ExecuteReader()
        try {
          while ($reader.Read()) {
            $row = @('B')
            for ($i = 0; $i -lt $reader.FieldCount; $i++) { $row += , $reader.GetValue($i) }
            Write-Line $row
          }
        } finally { $reader.Close() }
      }
      'write' {
        # The record may have been changed meanwhile, in ProScan or on another computer: it is
        # saved only when it still holds what the user read, unless the user chose to replace it
        $tx = $connection.BeginTransaction()
        $check = $connection.CreateCommand()
        $check.Transaction = $tx
        $check.CommandText =
          'SELECT Description, DoctorID, DiagnID FROM FluorTab WHERE ID = ? AND FileName = ?'
        Add-Param $check Integer ([int](Get-Arg 'PROSCAN_ID'))
        Add-Param $check VarWChar (Get-Arg 'PROSCAN_FILE')
        $reader = $check.ExecuteReader()
        $found = $false
        try {
          if ($reader.Read()) {
            $found = $true
            $text = As-Text $reader.GetValue(0)
            $doctor = As-Int $reader.GetValue(1)
            $diagnosis = As-Int $reader.GetValue(2)
          }
        } finally { $reader.Close() }
        if (-not $found) {
          $tx.Rollback()
          Write-Line @('N', 0)
          break
        }
        $same = ($text -ceq (As-Text (Get-Arg 'PROSCAN_OLD_TEXT'))) -and
          ($doctor -eq (As-Int (Get-Arg 'PROSCAN_OLD_DOCTOR'))) -and
          ($diagnosis -eq (As-Int (Get-Arg 'PROSCAN_OLD_DIAGNOSIS')))
        if (-not $same -and (Get-Arg 'PROSCAN_FORCE') -ne '1') {
          $tx.Rollback()
          Write-Line @('C', $text, $doctor, $diagnosis)
          break
        }
        $command = $connection.CreateCommand()
        $command.Transaction = $tx
        $command.CommandText = 'UPDATE FluorTab SET Description = ?, IsDescr = 1, ' +
          'DoctorID = ?, DiagnID = ? WHERE ID = ? AND FileName = ?'
        Add-Param $command LongVarWChar (Get-Arg 'PROSCAN_TEXT')
        Add-Param $command Integer ([int](Get-Arg 'PROSCAN_DOCTOR'))
        Add-Param $command Integer ([int](Get-Arg 'PROSCAN_DIAGNOSIS'))
        Add-Param $command Integer ([int](Get-Arg 'PROSCAN_ID'))
        Add-Param $command VarWChar (Get-Arg 'PROSCAN_FILE')
        $count = $command.ExecuteNonQuery()
        $tx.Commit()
        Write-Line @('N', $count)
      }
      default { throw 'Unknown mode' }
    }
  } finally { $connection.Close() }
} catch {
  # Tell a database busy with another program, worth trying again, from any other error. Jet
  # numbers its lock errors; its messages are translated, hence the numbers first
  $busyCodes = @(3006, 3008, 3009, 3045, 3050, 3186, 3187, 3188, 3189, 3197, 3202, 3211, 3212,
    3218, 3260)
  $busy = $false
  $exception = $_.Exception
  if ($exception -is [System.Data.OleDb.OleDbException]) {
    foreach ($err in $exception.Errors) {
      if ($busyCodes -contains [math]::Abs([int]$err.NativeError)) { $busy = $true }
    }
  }
  if ($exception.Message -match 'locked|in use|блок|занят|використовуєт') { $busy = $true }
  Write-Line @('E', $exception.Message, $(if ($busy) { '1' } else { '0' }))
  exit 2
}
