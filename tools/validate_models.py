#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
يتحقق من نماذج TFLite قبل تضمينها في التطبيق، بنفس عقد ModelContract داخل SmartFaceEngine.
لا يحتاج أي مكتبة خارجية: يقرأ بنية flatbuffers الخاصة بـ TFLite مباشرة.

الاستخدام:
    python3 tools/validate_models.py faceunlock-backend/src/main/assets
    python3 tools/validate_models.py --inspect some_model.tflite      # لطباعة الأشكال فقط

العقد:
  face_recognition.tflite : مدخل [1,S,S,3] float32 (S بين 96 و160)، مخرج واحد [1,D] (D بين 64 و1024)
  minifasnet.tflite       : مدخل [1,S,S,3] أو [1,3,S,S] float32 (S بين 64 و160)، مخرج واحد [1,3] (0=تزييف،1=حقيقي،2=تزييف)

رمز الخروج:
  1 إذا كان نموذج التعرّف مفقوداً/غير متوافق (لا يعمل شيء بدونه).
  1 لنموذج مكافحة التزييف فقط إذا طُلب ذلك (--strict-spoof أو REQUIRE_NEURAL_PAD_MODEL=1)،
    وإلا يُطبع تحذير بارز ويستمر البناء (يعمل التطبيق بوضع آمن: لا فتح ما لم يسمح المستخدم صراحةً).
"""
import argparse
import os
import struct
import sys

FLOAT32 = 0


class TfliteError(Exception):
    pass


def _u16(b, o):
    return struct.unpack_from('<H', b, o)[0]


def _u32(b, o):
    return struct.unpack_from('<I', b, o)[0]


def _i32(b, o):
    return struct.unpack_from('<i', b, o)[0]


def _i8(b, o):
    return struct.unpack_from('<b', b, o)[0]


def _field(b, table, index):
    """موضع الحقل index داخل الجدول، أو 0 إن كان غائباً (قيمته الافتراضية)."""
    vt = table - _i32(b, table)
    vsize = _u16(b, vt)
    slot = 4 + 2 * index
    if slot >= vsize:
        return 0
    off = _u16(b, vt + slot)
    return table + off if off else 0


def _vector(b, table, index):
    """(بداية العناصر، العدد) أو None."""
    f = _field(b, table, index)
    if not f:
        return None
    v = f + _u32(b, f)
    return v + 4, _u32(b, v)


def read_io(path):
    """يقرأ مداخل/مخارج أول subgraph: قائمة (shape, dtype) لكل منهما."""
    with open(path, 'rb') as fh:
        b = fh.read()
    if len(b) < 16:
        raise TfliteError('الملف قصير جداً')
    try:
        root = _u32(b, 0)
        subs = _vector(b, root, 2)            # Model.subgraphs
        if not subs or subs[1] < 1:
            raise TfliteError('لا يوجد subgraph')
        sg = subs[0] + _u32(b, subs[0])       # العنصر 0
        tensors = _vector(b, sg, 0)           # SubGraph.tensors
        ins = _vector(b, sg, 1)               # SubGraph.inputs
        outs = _vector(b, sg, 2)              # SubGraph.outputs
        if not tensors or not ins or not outs:
            raise TfliteError('subgraph ناقص')

        def tensor(i):
            if i < 0 or i >= tensors[1]:
                raise TfliteError('فهرس tensor خارج النطاق')
            t = tensors[0] + 4 * i
            t = t + _u32(b, t)
            shp = _vector(b, t, 0)            # Tensor.shape
            shape = [] if not shp else [_i32(b, shp[0] + 4 * k) for k in range(shp[1])]
            f = _field(b, t, 1)               # Tensor.type
            return shape, (_i8(b, f) if f else 0)

        inputs = [tensor(_i32(b, ins[0] + 4 * k)) for k in range(ins[1])]
        outputs = [tensor(_i32(b, outs[0] + 4 * k)) for k in range(outs[1])]
        return inputs, outputs
    except struct.error as e:
        raise TfliteError('بنية الملف تالفة: %s' % e)


def check_recognizer(inputs, outputs):
    if len(outputs) != 1:
        return 'نموذج التعرّف يجب أن يملك مخرجاً واحداً، وُجد %d' % len(outputs)
    if len(inputs) != 1:
        return 'نموذج التعرّف يجب أن يملك مدخلاً واحداً، وُجد %d' % len(inputs)
    (ishape, itype), (oshape, _) = inputs[0], outputs[0]
    if itype != FLOAT32:
        return 'مدخل نموذج التعرّف ليس float32'
    if len(ishape) != 4 or ishape[0] != 1 or ishape[3] != 3 or ishape[1] != ishape[2]:
        return 'مدخل التعرّف يجب أن يكون [1,S,S,3]، وُجد %s' % ishape
    if not 96 <= ishape[1] <= 160:
        return 'حجم مدخل التعرّف %d خارج 96..160' % ishape[1]
    if len(oshape) != 2 or oshape[0] != 1:
        return 'مخرج التعرّف يجب أن يكون [1,D]، وُجد %s' % oshape
    if not 64 <= oshape[1] <= 1024:
        return 'بُعد البصمة %d خارج 64..1024' % oshape[1]
    return None


def check_spoof(inputs, outputs):
    if len(outputs) != 1:
        return 'نموذج مكافحة التزييف يجب أن يملك مخرجاً واحداً، وُجد %d (طراز DTN/FaceAntiSpoofing بمخرجين غير مدعوم)' % len(outputs)
    if len(inputs) != 1:
        return 'نموذج مكافحة التزييف يجب أن يملك مدخلاً واحداً، وُجد %d' % len(inputs)
    (ishape, itype), (oshape, _) = inputs[0], outputs[0]
    if itype != FLOAT32:
        return 'مدخل نموذج مكافحة التزييف ليس float32'
    if len(ishape) != 4 or ishape[0] != 1:
        return 'مدخل مكافحة التزييف يجب أن يكون رباعي الأبعاد بدفعة 1، وُجد %s' % ishape
    nhwc = ishape[3] == 3 and ishape[1] == ishape[2]
    nchw = ishape[1] == 3 and ishape[2] == ishape[3]
    if not (nhwc or nchw):
        return 'مدخل مكافحة التزييف يجب أن يكون [1,S,S,3] أو [1,3,S,S]، وُجد %s' % ishape
    size = ishape[1] if nhwc else ishape[2]
    if not 64 <= size <= 160:
        return 'حجم مدخل مكافحة التزييف %d خارج 64..160' % size
    if len(oshape) != 2 or oshape[0] != 1 or oshape[1] != 3:
        return 'مخرج مكافحة التزييف يجب أن يكون [1,3] (MiniFASNet ثلاثي الفئات)، وُجد %s' % oshape
    return None


def _annot(kind, title, msg):
    # صيغة تعليقات GitHub Actions؛ تظهر بارزةً في صفحة التشغيل
    print('::%s title=%s::%s' % (kind, title, msg))


def validate_dir(assets, strict_spoof):
    failed = False
    plan = (
        ('face_recognition.tflite', check_recognizer, True),
        ('minifasnet.tflite', check_spoof, strict_spoof),
    )
    for name, checker, fatal in plan:
        path = os.path.join(assets, name)
        if not os.path.isfile(path) or os.path.getsize(path) == 0:
            msg = '%s غير موجود: لم ينجح تنزيله من أي رابط' % name
            reason = msg
        else:
            try:
                inputs, outputs = read_io(path)
                print('%s: inputs=%s outputs=%s (%d bytes)' % (name, inputs, outputs, os.path.getsize(path)))
                reason = checker(inputs, outputs)
            except TfliteError as e:
                reason = 'تعذّر قراءة %s: %s' % (name, e)
        if reason is None:
            print('OK  %s' % name)
            continue
        if fatal:
            _annot('error', 'نموذج غير صالح', reason)
            failed = True
        else:
            _annot('warning', 'نموذج مكافحة التزييف غير متوافق',
                   reason + ' — سيرفض التطبيق الفتح (فشل مغلق) حتى يضع نموذج MiniFASNet 80x80 ثلاثي الفئات صالحاً، '
                            'أو يوافق المستخدم صراحةً من الإعدادات على الوضع الهندسي فقط (أقل أمانًا).')
    return 0 if not failed else 1


def main(argv):
    ap = argparse.ArgumentParser()
    ap.add_argument('assets', nargs='?', help='مجلد assets الذي يحوي النماذج')
    ap.add_argument('--inspect', help='يطبع أشكال نموذج واحد فقط')
    ap.add_argument('--strict-spoof', action='store_true', help='افشل البناء إن كان نموذج مكافحة التزييف غير صالح')
    args = ap.parse_args(argv)

    if args.inspect:
        inputs, outputs = read_io(args.inspect)
        print('inputs :', inputs)
        print('outputs:', outputs)
        return 0
    if not args.assets:
        ap.error('مطلوب مجلد assets أو --inspect')
    strict = args.strict_spoof or os.environ.get('REQUIRE_NEURAL_PAD_MODEL', '') in ('1', 'true', 'TRUE', 'yes')
    return validate_dir(args.assets, strict)


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
