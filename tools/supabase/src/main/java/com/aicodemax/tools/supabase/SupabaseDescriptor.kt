package com.aicodemax.tools.supabase

import com.aicodemax.tools.registry.CapabilityLayer
import com.aicodemax.tools.registry.CapabilityStatus
import com.aicodemax.tools.registry.LayerCapability
import com.aicodemax.tools.registry.ToolDescriptor

/** Supabase connector: user-owned backend via PostgREST/Auth (devtool only, §24). */
fun supabaseDescriptorToday(): ToolDescriptor = ToolDescriptor(
    toolId = "supabase",
    displayName = "Supabase",
    version = "0.1.0",
    layers = listOf(
        LayerCapability(CapabilityLayer.UI, CapabilityStatus.AVAILABLE, "SupabaseScreen (keys + test + query) (CP-68)"),
        LayerCapability(CapabilityLayer.CONTROLLER, CapabilityStatus.AVAILABLE, "SupabaseClient"),
        LayerCapability(CapabilityLayer.CAPABILITY_API, CapabilityStatus.AVAILABLE, "health/auth/query/insert"),
        LayerCapability(CapabilityLayer.RUNTIME, CapabilityStatus.AVAILABLE, "PostgREST/Auth over HTTPS, user-owned project"),
        LayerCapability(CapabilityLayer.EXECUTION, CapabilityStatus.AVAILABLE, "via ToolGateway"),
        LayerCapability(CapabilityLayer.VERIFICATION, CapabilityStatus.PARTIAL, "HTTP status + raw body"),
        LayerCapability(CapabilityLayer.RECOVERY, CapabilityStatus.MISSING, "no retry queue"),
    ),
)
