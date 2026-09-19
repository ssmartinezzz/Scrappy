import SavedOutfitCard from './SavedOutfitCard';
import SavedPcCard from './SavedPcCard';

export default function ArmadoresPanel({
  savedOutfits = [], savedPcs = [],
  onDeleteSavedOutfit, onRenameSavedOutfit,
  onDeleteSavedPc, onRenameSavedPc,
}) {
  return (
    <div className="h-full overflow-y-auto">
      <div className="mx-auto max-w-[920px] px-[20px] py-[24px]">
        <p className="mb-[6px] text-eyebrow uppercase text-t3">Guardados</p>
        <h1 className="mb-[24px] text-display-2 text-t1">Armadores</h1>

        <section className="mb-[32px]">
          <h2 className="mb-[12px] text-[.85rem] font-bold text-t1">👕 Outfits guardados</h2>
          {savedOutfits.length === 0 ? (
            <p className="text-[.85rem] text-t3">Todavía no guardaste ningún outfit.</p>
          ) : (
            <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
              {savedOutfits.map(o => (
                <SavedOutfitCard
                  key={o.id}
                  outfit={o}
                  onDelete={onDeleteSavedOutfit}
                  onRename={onRenameSavedOutfit}
                />
              ))}
            </div>
          )}
        </section>

        <section>
          <h2 className="mb-[12px] text-[.85rem] font-bold text-t1">🖥 PCs guardadas</h2>
          {savedPcs.length === 0 ? (
            <p className="text-[.85rem] text-t3">Todavía no guardaste ninguna PC.</p>
          ) : (
            <div style={{ display:'flex', flexDirection:'column', gap:10, maxWidth:680 }}>
              {savedPcs.map(p => (
                <SavedPcCard
                  key={p.id}
                  pc={p}
                  onDelete={onDeleteSavedPc}
                  onRename={onRenameSavedPc}
                />
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
