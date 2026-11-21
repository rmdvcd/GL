/** Select only periods of this installation absorbed by the anchor license. */
export function absorbedBy(anchor) {
  return `EXISTS (SELECT 1 FROM redemptions anchor WHERE anchor.license_id = ${anchor}
    AND anchor.app_id = redemptions.app_id AND anchor.device_thumbprint = redemptions.device_thumbprint
    AND redemptions.expires_at <= anchor.expires_at)`;
}
