package net.osmand.plus.settings.datastorage.task;

import android.os.AsyncTask;

import androidx.annotation.NonNull;

import net.osmand.plus.settings.datastorage.DataStorageHelper.UpdateMemoryInfoUIAdapter;
import net.osmand.plus.settings.datastorage.item.DirectoryItem;
import net.osmand.plus.settings.datastorage.item.DirectoryItem.CheckingType;
import net.osmand.plus.settings.datastorage.item.MemoryItem;

import java.io.File;

import static net.osmand.plus.settings.datastorage.DataStorageFragment.UI_REFRESH_TIME_MS;
import static net.osmand.util.Algorithms.objectEquals;

public class RefreshUsedMemoryTask extends AsyncTask<MemoryItem, Void, Void> {
	private final UpdateMemoryInfoUIAdapter uiAdapter;
	private final File root;
	private final MemoryItem otherMemoryItem;
	private final String[] directoriesToSkip;
	private final String[] filePrefixesToSkip;
	private final String tag;
	private long lastRefreshTime;

	public RefreshUsedMemoryTask(UpdateMemoryInfoUIAdapter uiAdapter,
	                             MemoryItem otherMemoryItem,
	                             File root,
	                             String[] directoriesToSkip,
	                             String[] filePrefixesToSkip,
	                             String tag) {
		this.uiAdapter = uiAdapter;
		this.otherMemoryItem = otherMemoryItem;
		this.root = root;
		this.directoriesToSkip = directoriesToSkip;
		this.filePrefixesToSkip = filePrefixesToSkip;
		this.tag = tag;
	}

	@Override
	protected Void doInBackground(MemoryItem... items) {
		lastRefreshTime = System.currentTimeMillis();
		if (root.canRead()) {
			calculateMultiTypes(root, items);
		}
		return null;
	}

	private void calculateMultiTypes(File rootDir,
	                                 MemoryItem... items) {
		File[] subFiles = rootDir.listFiles();
		if (subFiles != null) {
			for (File file : subFiles) {
				if (isCancelled()) break;

				if (file.isDirectory()) {
					if (!shouldSkipDirectory(file)) {
						processDirectory(file, items);
					}

				} else if (file.isFile()) {
					if (!shouldSkipFile(file)) {
						processFile(rootDir, file, items);
					}
				}
				refreshUI();
			}
		}
	}

	private boolean shouldSkipDirectory(@NonNull File dir) {
		if (directoriesToSkip != null) {
			for (String dirToSkipPath : directoriesToSkip) {
				String dirPath = dir.getAbsolutePath();
				if (objectEquals(dirPath, dirToSkipPath)) {
					return true;
				}
			}
		}
		return false;
	}

	private boolean shouldSkipFile(@NonNull File file) {
		if (filePrefixesToSkip != null) {
			String fileName = file.getName().toLowerCase();
			for (String prefixToAvoid : filePrefixesToSkip) {
				String prefix = prefixToAvoid.toLowerCase();
				if (fileName.startsWith(prefix)) {
					return true;
				}
			}
		}
		return false;
	}

	private void processDirectory(@NonNull File directory,
	                              @NonNull MemoryItem... items) {
		String directoryPath = directory.getAbsolutePath();
		for (MemoryItem memoryItem : items) {
			DirectoryItem[] allowedDirectories = memoryItem.getDirectories();
			if (allowedDirectories != null) {
				for (DirectoryItem allowedDir : allowedDirectories) {
					String allowedDirPath = allowedDir.getAbsolutePath();
					if (objectEquals(directoryPath, allowedDirPath)
							|| (directoryPath.startsWith(allowedDirPath))) {
						if (allowedDir.shouldProcessInternalDirectories()) {
							calculateMultiTypes(directory, items);
							return;
						} else if (allowedDir.shouldSkipUnmatchedInDirectory()) {
							return;
						}
					}
				}
			}
		}
		// Current directory did not match to any type
		otherMemoryItem.addBytes(calculateFolderSize(directory));
	}

	private void processFile(@NonNull File rootDir,
	                         @NonNull File file,
	                         @NonNull MemoryItem... items) {
		for (MemoryItem item : items) {
			DirectoryItem[] allowedDirectories = item.getDirectories();
			if (allowedDirectories == null) continue;
			String rootDirPath = rootDir.getAbsolutePath();

			for (DirectoryItem allowedDir : allowedDirectories) {
				String allowedDirPath = allowedDir.getAbsolutePath();
				boolean processInternal = allowedDir.shouldProcessInternalDirectories();
				if (objectEquals(rootDirPath, allowedDirPath)
						|| (rootDirPath.startsWith(allowedDirPath) && processInternal)) {
					CheckingType checkingType = allowedDir.getCheckingType();
					switch (checkingType) {
						case EXTENSIONS: {
							if (isSuitableExtension(file, item)) {
								item.addBytes(file.length());
								return;
							}
							break;
						}
						case PREFIX: {
							if (isSuitablePrefix(file, item)) {
								item.addBytes(file.length());
								return;
							}
							break;
						}
					}
					if (allowedDir.shouldSkipUnmatchedInDirectory()) {
						return;
					}
				}
			}
		}
		// Current file did not match any type
		otherMemoryItem.addBytes(file.length());
	}

	private boolean isSuitableExtension(@NonNull File file,
	                                    @NonNull MemoryItem item) {
		String[] extensions = item.getExtensions();
		if (extensions != null) {
			for (String extension : extensions) {
				if (file.getAbsolutePath().endsWith(extension)) {
					return true;
				}
			}
		}
		return extensions == null;
	}

	private boolean isSuitablePrefix(@NonNull File file,
	                                 @NonNull MemoryItem item) {
		String[] prefixes = item.getPrefixes();
		if (prefixes != null) {
			for (String prefix : prefixes) {
				if (file.getName().toLowerCase().startsWith(prefix.toLowerCase())) {
					return true;
				}
			}
		}
		return prefixes == null;
	}

	private long calculateFolderSize(@NonNull File dir) {
		long bytes = 0;
		if (dir.isDirectory()) {
			File[] files = dir.listFiles();
			if (files == null) return 0;

			for (File file : files) {
				if (isCancelled()) {
					break;
				}
				if (file.isDirectory()) {
					bytes += calculateFolderSize(file);
				} else if (file.isFile()) {
					bytes += file.length();
				}
			}
		}
		return bytes;
	}

	@Override
	protected void onProgressUpdate(Void... values) {
		super.onProgressUpdate(values);
		if (uiAdapter != null) {
			uiAdapter.onMemoryInfoUpdate();
		}
	}

	@Override
	protected void onPostExecute(Void aVoid) {
		super.onPostExecute(aVoid);
		if (uiAdapter != null) {
			uiAdapter.onFinishUpdating(tag);
		}
	}

	private void refreshUI() {
		long currentTime = System.currentTimeMillis();
		if ((currentTime - lastRefreshTime) > UI_REFRESH_TIME_MS) {
			lastRefreshTime = currentTime;
			publishProgress();
		}
	}
}
