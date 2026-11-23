#!/usr/bin/env python3
# اختبار المُدقِّق على نماذج TFLite صناعية تُبنى بمكتبة flatbuffers (تُتخطى إن لم تكن مثبّتة).
import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
import validate_models as vm

try:
    import flatbuffers
except ImportError:  # pragma: no cover
    flatbuffers = None


def build_tflite(inputs, outputs):
    """يبني ملف TFLite صغيراً (subgraph واحد) بنفس مواضع الحقول في مخطط TFLite الحقيقي."""
    b = flatbuffers.Builder(1024)

    def vec_i32(values):
        b.StartVector(4, len(values), 4)
        for v in reversed(values):
            b.PrependInt32(v)
        return b.EndVector()

    tensor_offsets = []
    io_index = []
    for shape, dtype in inputs + outputs:
        sv = vec_i32(shape)
        b.StartObject(8)
        b.PrependUOffsetTRelativeSlot(0, sv, 0)      # shape
        b.PrependInt8Slot(1, dtype, 0)               # type
        tensor_offsets.append(b.EndObject())
    n_in = len(inputs)

    b.StartVector(4, len(tensor_offsets), 4)
    for t in reversed(tensor_offsets):
        b.PrependUOffsetTRelative(t)
    tensors = b.EndVector()
    ins = vec_i32(list(range(n_in)))
    outs = vec_i32(list(range(n_in, n_in + len(outputs))))

    b.StartObject(5)
    b.PrependUOffsetTRelativeSlot(0, tensors, 0)
    b.PrependUOffsetTRelativeSlot(1, ins, 0)
    b.PrependUOffsetTRelativeSlot(2, outs, 0)
    sg = b.EndObject()

    b.StartVector(4, 1, 4)
    b.PrependUOffsetTRelative(sg)
    subs = b.EndVector()

    b.StartObject(8)
    b.PrependUint32Slot(0, 3, 0)                     # version
    b.PrependUOffsetTRelativeSlot(2, subs, 0)        # subgraphs
    model = b.EndObject()
    b.Finish(model, b'TFL3')
    return bytes(b.Output())


@unittest.skipIf(flatbuffers is None, 'flatbuffers غير مثبّتة')
class ValidateModelsTest(unittest.TestCase):

    def write(self, data):
        fd, path = tempfile.mkstemp(suffix='.tflite')
        os.write(fd, data)
        os.close(fd)
        self.addCleanup(os.remove, path)
        return path

    def test_reads_shapes_back(self):
        path = self.write(build_tflite([([1, 112, 112, 3], 0)], [([1, 192], 0)]))
        ins, outs = vm.read_io(path)
        self.assertEqual(ins, [([1, 112, 112, 3], 0)])
        self.assertEqual(outs, [([1, 192], 0)])

    def test_recognizer_contract(self):
        ok = vm.read_io(self.write(build_tflite([([1, 112, 112, 3], 0)], [([1, 192], 0)])))
        self.assertIsNone(vm.check_recognizer(*ok))
        bad = vm.read_io(self.write(build_tflite([([1, 3, 112, 112], 0)], [([1, 192], 0)])))
        self.assertIsNotNone(vm.check_recognizer(*bad))
        quant = vm.read_io(self.write(build_tflite([([1, 112, 112, 3], 3)], [([1, 192], 0)])))
        self.assertIsNotNone(vm.check_recognizer(*quant))

    def test_spoof_accepts_minifasnet_layouts(self):
        nhwc = vm.read_io(self.write(build_tflite([([1, 80, 80, 3], 0)], [([1, 3], 0)])))
        self.assertIsNone(vm.check_spoof(*nhwc))
        nchw = vm.read_io(self.write(build_tflite([([1, 3, 80, 80], 0)], [([1, 3], 0)])))
        self.assertIsNone(vm.check_spoof(*nchw))

    def test_spoof_rejects_the_dtn_model_the_ci_used_to_download(self):
        # FaceAntiSpoofing.tflite (طراز DTN): مدخل 256×256 ومخرجان بثماني قيم
        dtn = vm.read_io(self.write(build_tflite([([1, 256, 256, 3], 0)], [([1, 8], 0), ([1, 8], 0)])))
        self.assertIsNotNone(vm.check_spoof(*dtn))
        self.assertIn('مخرجاً واحداً', vm.check_spoof(*dtn))
        two_class = vm.read_io(self.write(build_tflite([([1, 80, 80, 3], 0)], [([1, 2], 0)])))
        self.assertIsNotNone(vm.check_spoof(*two_class))

    def test_garbage_is_rejected_not_crashing(self):
        for blob in (b'', b'abc', os.urandom(64), b'\x08\x00\x00\x00TFL3' + b'\xff' * 40):
            path = self.write(blob)
            with self.assertRaises(vm.TfliteError):
                vm.read_io(path)

    def test_directory_validation_exit_codes(self):
        with tempfile.TemporaryDirectory() as d:
            with open(os.path.join(d, 'face_recognition.tflite'), 'wb') as f:
                f.write(build_tflite([([1, 112, 112, 3], 0)], [([1, 192], 0)]))
            with open(os.path.join(d, 'minifasnet.tflite'), 'wb') as f:
                f.write(build_tflite([([1, 256, 256, 3], 0)], [([1, 8], 0), ([1, 8], 0)]))
            self.assertEqual(vm.validate_dir(d, strict_spoof=False), 0)   # تحذير فقط
            self.assertEqual(vm.validate_dir(d, strict_spoof=True), 1)    # يفشل عند الطلب
            os.remove(os.path.join(d, 'face_recognition.tflite'))
            self.assertEqual(vm.validate_dir(d, strict_spoof=False), 1)   # التعرّف مفقود => فشل


if __name__ == '__main__':
    unittest.main()
