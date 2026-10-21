const MAX_FILE_SIZE = 12 * 1024 * 1024;
const ALLOWED_TYPES = new Set(["image/png", "image/jpeg", "image/webp"]);
const samples = { first: null, second: null };

const compareButton = document.querySelector("#compare-button");
const uploadError = document.querySelector("#upload-error");
const results = document.querySelector("#results");

function setError(message = "") {
  uploadError.textContent = message;
}

function updateButton() {
  compareButton.disabled = !(samples.first && samples.second);
}

function setSample(slot, file) {
  if (!file) return;
  if (!ALLOWED_TYPES.has(file.type)) {
    setError("Choose a PNG, JPG, or WEBP image.");
    return;
  }
  if (file.size > MAX_FILE_SIZE) {
    setError("That image is too large. Choose a file under 12 MB.");
    return;
  }

  const reader = new FileReader();
  reader.addEventListener("load", () => {
    const image = new Image();
    image.addEventListener("load", () => {
      samples[slot] = { file, image };
      const zone = document.querySelector(`#drop-${slot}`);
      zone.classList.add("has-image");
      zone.querySelector(".preview").src = reader.result;
      zone.querySelector(".file-name").textContent = file.name;
      setError("");
      updateButton();
      results.hidden = true;
    }, { once: true });
    image.addEventListener("error", () => setError("This image could not be read. Try another file."), { once: true });
    image.src = reader.result;
  }, { once: true });
  reader.addEventListener("error", () => setError("This image could not be read. Try another file."), { once: true });
  reader.readAsDataURL(file);
}

function clearSample(slot) {
  samples[slot] = null;
  const zone = document.querySelector(`#drop-${slot}`);
  zone.classList.remove("has-image");
  zone.querySelector(".preview").removeAttribute("src");
  zone.querySelector(".file-name").textContent = "";
  zone.querySelector("input").value = "";
  setError("");
  results.hidden = true;
  updateButton();
}

for (const slot of ["first", "second"]) {
  const input = document.querySelector(`#file-${slot}`);
  const zone = document.querySelector(`#drop-${slot}`);
  input.addEventListener("change", () => setSample(slot, input.files[0]));
  zone.addEventListener("keydown", (event) => {
    if (event.target === zone && (event.key === "Enter" || event.key === " ")) {
      event.preventDefault();
      input.click();
    }
  });
  zone.addEventListener("dragover", (event) => {
    event.preventDefault();
    zone.classList.add("drag-over");
  });
  zone.addEventListener("dragleave", () => zone.classList.remove("drag-over"));
  zone.addEventListener("drop", (event) => {
    event.preventDefault();
    zone.classList.remove("drag-over");
    setSample(slot, event.dataTransfer.files[0]);
  });
  zone.querySelector(".remove-image").addEventListener("click", (event) => {
    event.preventDefault();
    event.stopPropagation();
    clearSample(slot);
  });
}

function extractFeatures(image) {
  const scale = Math.min(1, 1100 / Math.max(image.naturalWidth, image.naturalHeight));
  const width = Math.max(1, Math.round(image.naturalWidth * scale));
  const height = Math.max(1, Math.round(image.naturalHeight * scale));
  const canvas = document.createElement("canvas");
  canvas.width = width;
  canvas.height = height;
  const context = canvas.getContext("2d", { willReadFrequently: true });
  context.drawImage(image, 0, 0, width, height);

  const rgba = context.getImageData(0, 0, width, height).data;
  const gray = new Uint8Array(width * height);
  const histogram = new Uint32Array(256);
  for (let i = 0, p = 0; i < rgba.length; i += 4, p += 1) {
    const value = Math.round(0.299 * rgba[i] + 0.587 * rgba[i + 1] + 0.114 * rgba[i + 2]);
    gray[p] = value;
    histogram[value] += 1;
  }

  const background = percentile(histogram, gray.length, 0.9);
  const threshold = Math.min(220, background - 32);
  let minX = width, minY = height, maxX = -1, maxY = -1, inkCount = 0, neighborCount = 0;
  const ink = new Uint8Array(width * height);

  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const index = y * width + x;
      if (gray[index] >= threshold) continue;
      ink[index] = 1;
      inkCount += 1;
      minX = Math.min(minX, x);
      minY = Math.min(minY, y);
      maxX = Math.max(maxX, x);
      maxY = Math.max(maxY, y);
      if (x > 0 && ink[index - 1]) neighborCount += 1;
    }
  }

  if (inkCount < 80 || maxX < 0) {
    throw new Error("We couldn't find enough handwriting in one of the images. Try a clearer, closer photo.");
  }

  const boxWidth = maxX - minX + 1;
  const boxHeight = maxY - minY + 1;
  const grid = [];
  const normalizedWidth = 160;
  const normalizedHeight = 100;
  const normalized = new Uint8Array(normalizedWidth * normalizedHeight);
  for (let y = 0; y < normalizedHeight; y += 1) {
    const sourceY = minY + Math.min(boxHeight - 1, Math.floor(y * boxHeight / normalizedHeight));
    for (let x = 0; x < normalizedWidth; x += 1) {
      const sourceX = minX + Math.min(boxWidth - 1, Math.floor(x * boxWidth / normalizedWidth));
      normalized[y * normalizedWidth + x] = ink[sourceY * width + sourceX];
    }
  }
  const cellsX = 16, cellsY = 10;
  for (let cellY = 0; cellY < cellsY; cellY += 1) {
    for (let cellX = 0; cellX < cellsX; cellX += 1) {
      let count = 0;
      for (let y = cellY * normalizedHeight / cellsY; y < (cellY + 1) * normalizedHeight / cellsY; y += 1) {
        for (let x = cellX * normalizedWidth / cellsX; x < (cellX + 1) * normalizedWidth / cellsX; x += 1) {
          count += normalized[y * normalizedWidth + x];
        }
      }
      grid.push(count / ((normalizedWidth / cellsX) * (normalizedHeight / cellsY)));
    }
  }

  return {
    grid,
    density: inkCount / (boxWidth * boxHeight),
    aspect: boxWidth / boxHeight,
    strokeWeight: neighborCount / Math.max(inkCount, 1),
    pixelCount: inkCount,
  };
}

function percentile(histogram, total, fraction) {
  const target = total * fraction;
  let count = 0;
  for (let value = 0; value < histogram.length; value += 1) {
    count += histogram[value];
    if (count >= target) return value;
  }
  return 255;
}

function vectorSimilarity(first, second) {
  let dot = 0, normFirst = 0, normSecond = 0;
  for (let i = 0; i < first.length; i += 1) {
    dot += first[i] * second[i];
    normFirst += first[i] * first[i];
    normSecond += second[i] * second[i];
  }
  if (!normFirst || !normSecond) return 0;
  return Math.max(0, Math.min(1, dot / Math.sqrt(normFirst * normSecond)));
}

function scalarSimilarity(first, second) {
  const larger = Math.max(first, second);
  return larger === 0 ? 1 : Math.max(0, 1 - Math.abs(first - second) / larger);
}

function showScore(value, title, description) {
  const rounded = Math.round(value);
  document.querySelector("#score-value").textContent = rounded;
  document.querySelector("#score-ring").style.background =
    `conic-gradient(var(--green) ${rounded * 3.6}deg, #e8eee8 ${rounded * 3.6}deg)`;
  document.querySelector("#result-title").textContent = title;
  document.querySelector("#result-description").textContent = description;
}

function setTrait(name, value) {
  const rounded = Math.round(value * 100);
  document.querySelector(`#trait-${name}`).style.width = `${rounded}%`;
  document.querySelector(`#trait-${name}-value`).textContent = `${rounded}%`;
}

compareButton.addEventListener("click", () => {
  if (!samples.first || !samples.second) return;
  compareButton.disabled = true;
  compareButton.querySelector("span").textContent = "Comparing…";
  setError("");

  window.setTimeout(() => {
    try {
      const first = extractFeatures(samples.first.image);
      const second = extractFeatures(samples.second.image);
      const similarities = {
        shape: vectorSimilarity(first.grid, second.grid),
        weight: scalarSimilarity(first.strokeWeight, second.strokeWeight),
        density: scalarSimilarity(first.density, second.density),
        proportions: scalarSimilarity(first.aspect, second.aspect),
      };
      const score = (
        similarities.shape * 0.55 +
        similarities.weight * 0.18 +
        similarities.density * 0.14 +
        similarities.proportions * 0.13
      ) * 100;

      if (score >= 78) {
        showScore(score, "Many traits look alike", "The samples share several broad visual patterns. That alone cannot confirm they were written by the same person.");
      } else if (score >= 55) {
        showScore(score, "Some traits look alike", "The samples show a mix of similar and different visual patterns. Consider the writing conditions and image quality.");
      } else {
        showScore(score, "Few traits look alike", "The samples differ across several broad visual patterns. Different wording or writing conditions can also cause differences.");
      }
      for (const [trait, similarity] of Object.entries(similarities)) setTrait(trait, similarity);
      results.hidden = false;
      results.scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      setError(error instanceof Error ? error.message : "We couldn't compare these images. Try clearer photos.");
    } finally {
      compareButton.disabled = !(samples.first && samples.second);
      compareButton.querySelector("span").textContent = "Compare handwriting";
    }
  }, 30);
});

document.querySelector("#reset-button").addEventListener("click", () => {
  clearSample("first");
  clearSample("second");
  window.scrollTo({ top: 0, behavior: "smooth" });
});
