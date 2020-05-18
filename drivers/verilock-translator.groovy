/**
 *  Copyright 2017 Eric Maycock
 *  Ported to Hubitat by Dominick Meglio
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *	  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */
metadata {
	definition(name: "Verilock Translator", namespace: "erocm123", author: "Eric Maycock", vid: "generic-lock") {
		capability "Lock"
		capability "Contact Sensor"
		capability "Configuration"
		capability "Sensor"
		capability "Refresh"

		fingerprint deviceId: "0x414E", inClusters: "0x5E,0x86,0x70,0x8E,0x85,0x59,0x7A,0x71,0x22,0x5A,0x73,0x72,0x60"
	}
}

preferences {
	input "debugOutput", "bool", title: "Enable debug logging?", defaultValue: true, displayDuringSetup: false, required: false
}

def parse(String description) {
	def result = null
	if (description.startsWith("Err")) {
		result = createEvent(descriptionText: description, isStateChange: true)
	} 
	else if (description != "updated") {
		def cmd = zwave.parse(description, [0x20: 1, 0x84: 1, 0x98: 1, 0x56: 1, 0x60: 3])
		if (cmd) {
			result = zwaveEvent(cmd)
		}
	}
	logDebug "'$description' parsed to $result"
	return result
}

def zwaveEvent(hubitat.zwave.commands.wakeupv1.WakeUpNotification cmd) {
	[createEvent(descriptionText: "${device.displayName} woke up", isStateChange: true),
		response(["delay 2000", zwave.wakeUpV1.wakeUpNoMoreInformation().format()])
	]
}

def createOrGetChildDevice(ep) {
	def childDevice = childDevices.find {
		it.deviceNetworkId == "${device.deviceNetworkId}-ep${ep}"
	}
	if (!childDevice) {
		logDebug "Child not found for endpoint. Creating one now"
		childDevice = addChildDevice("erocm123", "Lockable Door/Window Child Device", "${device.deviceNetworkId}-ep${ep}",
			[completedSetup: true, label: "${device.displayName} Window ${ep}",
				isComponent: false, componentName: "ep$ep", componentLabel: "Window $ep"
			])
	}
	return childDevice
}

def zwaveEvent(hubitat.zwave.commands.notificationv3.NotificationReport cmd, ep = null) {
	if (ep == null) {
		log.warn "Received a notification report for a null endpoint"
		return
	}
	def evtName
	def evtValue
	switch (cmd.event) {
		case 1:
			evtName = "lock"
			evtValue = "locked"
			break;
		case 2:
			evtName = "lock"
			evtValue = "unlocked"
			break;
		case 22:
			evtName = "contact"
			evtValue = "open"
			break;
		case 23:
			evtName = "contact"
			evtValue = "closed"
			break;
	}

	def childDevice = createOrGetChildDevice(ep)
	childDevice.sendEvent(name: evtName, value: evtValue)

	def allLocked = true
	def allClosed = true
	childDevices.each { n->
		if (n.currentState("contact") && n.currentState("contact").value != "closed") allClosed = false
		if (n.currentState("lock") && n.currentState("lock").value != "locked") allLocked = false
	}
	def events = []
	if (allLocked) {
		sendEvent([name: "lock", value: "locked"])
	} else {
		sendEvent([name: "lock", value: "unlocked"])
	}
	if (allClosed) {
		sendEvent([name: "contact", value: "closed"])
	} else {
		sendEvent([name: "contact", value: "open"])
	}
}

def zwaveEvent(hubitat.zwave.commands.batteryv1.BatteryReport cmd, ep = null) {
log.debug "Received battery report ${cmd} ${ep}"
	if (ep == null) {
		log.warn "Received a battery report for a null endpoint"
		return
	}
	def value = cmd.batteryLevel
	def descriptionText = null
	if (cmd.batteryLevel == 0xFF) {
		map.value = 1
		descriptionText = "${device.displayName} has a low battery"
	}

	def childDevice = createOrGetChildDevice(ep)
	childDevice.sendEvent(name: "battery", value: value, unit: "%", descriptionText: descriptionText)
}

private channelNumber(String dni) {
	dni.split("-ep")[-1] as Integer
}

def zwaveEvent(hubitat.zwave.commands.associationv2.AssociationGroupingsReport cmd) {
	state.groups = cmd.supportedGroupings
	if (cmd.supportedGroupings > 1) {
		[response(zwave.associationGrpInfoV1.associationGroupInfoGet(groupingIdentifier: 2, listMode: 1))]
	}
}

def zwaveEvent(hubitat.zwave.commands.associationgrpinfov1.AssociationGroupInfoReport cmd) {
	def cmds = []
	for (def i = 2; i <= state.groups; i++) {
		cmds << response(zwave.multiChannelAssociationV2.multiChannelAssociationSet(groupingIdentifier: i, nodeId: zwaveHubNodeId))
	}
	cmds
}


def zwaveEvent(hubitat.zwave.commands.multichannelv3.MultiChannelCmdEncap cmd) {
	def encapsulatedCommand = cmd.encapsulatedCommand([0x32: 3, 0x25: 1, 0x20: 1])
	if (encapsulatedCommand) {
		logDebug encapsulatedCommand
		zwaveEvent(encapsulatedCommand, cmd.sourceEndPoint as Integer)
	}
}

def zwaveEvent(hubitat.zwave.Command cmd) {
	createEvent(descriptionText: "$device.displayName: $cmd", isStateChange: true)
}

def configure() {
	commands([
		zwave.multiChannelV3.multiChannelEndPointGet()
	], 800)
}

def refresh() {
	commands([
		zwave.batteryV1.batteryGet()
	], 800)
}

private command(hubitat.zwave.Command cmd) {
	if (state.sec) {
		zwave.securityV1.securityMessageEncapsulation().encapsulate(cmd).format()
	} else {
		cmd.format()
	}
}

private commands(commands, delay = 200) {
	delayBetween(commands.collect {
		command(it)
	}, delay)
}

def logDebug(msg) {
	if (settings?.debugOutput) {
		log.debug msg
	}
}