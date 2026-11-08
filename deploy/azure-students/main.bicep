targetScope = 'subscription'

@description('Resource group created by this subscription-scope deployment.')
param resourceGroupName string = 'destinedfx-students'

@description('Azure region selected by the owner. Pick a region that offers the selected VM size.')
param location string = 'eastus'

@description('One Linux VM only. These are the sizes listed on the Azure for Students page.')
@allowed([
  'Standard_B1s'
  'Standard_B2ats_v2'
  'Standard_B2pts_v2'
])
param vmSize string = 'Standard_B1s'

@description('30 GB is the conservative student-free disk guard for this kit. Larger disks are rejected.')
@allowed([30])
param osDiskSizeGb int = 30

@description('Azure VM name.')
@minLength(3)
@maxLength(15)
param vmName string = 'destinedfxvm'

@description('Linux administrator username. Password authentication is disabled.')
@minLength(1)
@maxLength(32)
param adminUsername string = 'destinedfx'

@description('SSH public key pasted by the owner in Cloud Shell. Never put a private key here.')
param sshPublicKey string

@description('Email for the USD 1 monthly budget alert. Budget alerts do not cap usage.')
param budgetEmail string

resource resourceGroup 'Microsoft.Resources/resourceGroups@2024-03-01' = {
  name: resourceGroupName
  location: location
}

module runtime './resources.bicep' = {
  name: 'destinedfx-runtime'
  scope: resourceGroup
  params: {
    location: location
    vmSize: vmSize
    osDiskSizeGb: osDiskSizeGb
    vmName: vmName
    adminUsername: adminUsername
    sshPublicKey: sshPublicKey
  }
}

resource budget 'Microsoft.Consumption/budgets@2023-11-01' = {
  name: 'destinedfx-${uniqueString(subscription().id, resourceGroupName)}'
  properties: {
    category: 'Cost'
    amount: 1
    timeGrain: 'Monthly'
    timePeriod: {
      startDate: '2026-01-01'
    }
    notifications: {
      eightyPercent: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 80
        contactEmails: [budgetEmail]
        contactRoles: []
        contactGroups: []
      }
      hundredPercent: {
        enabled: true
        operator: 'GreaterThan'
        threshold: 100
        contactEmails: [budgetEmail]
        contactRoles: []
        contactGroups: []
      }
    }
  }
}

output resourceGroupName string = resourceGroup.name
output vmName string = runtime.outputs.vmName
output privateIp string = runtime.outputs.privateIp
output runCommand string = runtime.outputs.runCommand
output architectureNote string = runtime.outputs.architectureNote
