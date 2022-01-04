package org.medicmobile.webapp.mobile;

import static android.Manifest.permission.ACCESS_COARSE_LOCATION;
import static android.Manifest.permission.ACCESS_FINE_LOCATION;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;

import androidx.core.content.ContextCompat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28)
public class EmbeddedBrowserActivityTest {

	@Test
	public void isMigrationRunning_returnsFlagCorrectly() {
		EmbeddedBrowserActivity embeddedBrowserActivity = new EmbeddedBrowserActivity();

		embeddedBrowserActivity.setMigrationRunning(true);
		assertTrue(embeddedBrowserActivity.isMigrationRunning());

		embeddedBrowserActivity.setMigrationRunning(false);
		assertFalse(embeddedBrowserActivity.isMigrationRunning());
	}

	@Test
	public void getLocationPermissions_withPermissionsGranted_returnsTrue() {
		try(
			MockedStatic<ContextCompat> contextCompatMock = mockStatic(ContextCompat.class);
			MockedStatic<MedicLog> medicLogMock = mockStatic(MedicLog.class);
			) {
			contextCompatMock.when(() -> ContextCompat.checkSelfPermission(any(), eq(ACCESS_FINE_LOCATION))).thenReturn(PERMISSION_GRANTED);
			contextCompatMock.when(() -> ContextCompat.checkSelfPermission(any(), eq(ACCESS_COARSE_LOCATION))).thenReturn(PERMISSION_GRANTED);
			EmbeddedBrowserActivity embeddedBrowserActivity = new EmbeddedBrowserActivity();

			assertTrue(embeddedBrowserActivity.getLocationPermissions());
			medicLogMock.verify(() -> MedicLog.trace(eq(embeddedBrowserActivity), eq("getLocationPermissions() :: already granted")));
		}
	}
}
