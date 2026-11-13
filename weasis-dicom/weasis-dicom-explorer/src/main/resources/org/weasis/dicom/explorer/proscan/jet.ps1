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
    else { [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes([string]$f)) }
  }
  [Console]::Out.WriteLine(($encoded -join ' '))
}

function Add-Param($command, [System.Data.OleDb.OleDbType]$type, $value) {
  $p = $command.Parameters.Add('p' + $command.Parameters.Count, $type)
  $p.Value = $value
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
  $source = 'Provider=Microsoft.Jet.OLEDB.4.0;Data Source="' + (Get-Arg 'PROSCAN_DB') + '"'
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
      'catalog' {
        Write-Rows $connection 'D' 'SELECT ID, FIO FROM DoctorsSPR ORDER BY FIO'
        Write-Rows $connection 'G' 'SELECT ID, DiagnosName FROM DiagnosesSPR ORDER BY DiagnosName'
      }
      'write' {
        $command = $connection.CreateCommand()
        $command.CommandText = 'UPDATE FluorTab SET Description = ?, IsDescr = 1, ' +
          'DoctorID = ?, DiagnID = ? WHERE ID = ? AND FileName = ?'
        Add-Param $command LongVarWChar (Get-Arg 'PROSCAN_TEXT')
        Add-Param $command Integer ([int](Get-Arg 'PROSCAN_DOCTOR'))
        Add-Param $command Integer ([int](Get-Arg 'PROSCAN_DIAGNOSIS'))
        Add-Param $command Integer ([int](Get-Arg 'PROSCAN_ID'))
        Add-Param $command VarWChar (Get-Arg 'PROSCAN_FILE')
        Write-Line @('N', $command.ExecuteNonQuery())
      }
      default { throw 'Unknown mode' }
    }
  } finally { $connection.Close() }
} catch {
  Write-Line @('E', $_.Exception.Message)
  exit 2
}
