#!/bin/bash
# يرسم معاينات الكبسولة وحلقة التسجيل (PNG) بنفس كود الهندسة الذي يعمل على الجهاز.
# المتطلبات: JDK كامل (javac). الاستخدام:  tools/preview/render.sh [مجلد_الإخراج]
set -e
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/tools/preview/out}"
JAVAC="${JAVAC:-javac}"
SRC="$ROOT/faceunlock/src/main/java/ax/nd/faceunlock/overlay"
mkdir -p "$OUT/classes"
# EnrollRingView يعتمد على Android فيُستثنى؛ باقي الملفات جافا نقية
$JAVAC -d "$OUT/classes" "$ROOT"/tools/preview/*.java $(ls "$SRC"/*.java | grep -v EnrollRingView.java)
java -Djava.awt.headless=true -cp "$OUT/classes" CapsulePreview "$OUT"
java -Djava.awt.headless=true -cp "$OUT/classes" RingPreview "$OUT"
echo "تم: $OUT"
